package com.example.client.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    @Test
    fun startsSignedOut_whenNothingIsStored() {
        val manager = SessionManager(FakeTokenStorage())

        assertNull(manager.currentRole.value)
        assertNull(manager.token())
    }

    @Test
    fun restoresTokenAndRole_afterProcessRestart() {
        val storage = FakeTokenStorage()
        SessionManager(storage).startSession("jwt-1", UserRole.VETTING, null, "vetting_test_user")

        val restarted = SessionManager(storage) // new process, same persisted storage

        assertEquals(UserRole.VETTING, restarted.currentRole.value)
        assertEquals("jwt-1", restarted.token())
        assertEquals("vetting_test_user", restarted.currentUsername())
    }

    // #70: records are only ever sent by the person who captured them, so a session with no username (kept from before it
    // was recorded) cannot be tied to anyone's records. It ends and the user signs in again, rather than syncing as nobody.
    @Test
    fun aStoredSessionWithoutAUsername_isEnded_andRemovedFromStorage() {
        val storage = FakeTokenStorage(Session("old-jwt", UserRole.CBO_COLLECTION, "cbo-7", username = null))

        val manager = SessionManager(storage)

        assertNull(manager.currentRole.value)
        assertNull(manager.token())
        assertNull(manager.currentUsername())
        assertNull(storage.load())
    }

    @Test
    fun aStoredSessionWithABlankUsername_isEnded_too() {
        val storage = FakeTokenStorage(Session("old-jwt", UserRole.VETTING, username = "  "))

        assertNull(SessionManager(storage).currentRole.value)
        assertNull(storage.load())
    }

    @Test
    fun currentUsername_isWhoeverIsSignedIn_andNullOnceSignedOut() {
        val manager = SessionManager(FakeTokenStorage())
        assertNull(manager.currentUsername())

        manager.startSession("jwt-1", UserRole.CBO_COLLECTION, "cbo-7", "collector_one")
        assertEquals("collector_one", manager.currentUsername())

        manager.endSession()
        assertNull(manager.currentUsername())

        manager.startSession("jwt-2", UserRole.CBO_COLLECTION, "cbo-7", "collector_two")
        assertEquals("collector_two", manager.currentUsername())
    }

    @Test
    fun startSession_publishesRole_andPersistsBoth() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)

        manager.startSession("jwt-1", UserRole.ADMIN)

        assertEquals(UserRole.ADMIN, manager.currentRole.value)
        assertEquals("jwt-1", storage.load()?.token)
        assertEquals(UserRole.ADMIN, storage.load()?.role)
    }

    @Test
    fun endSession_clearsMemoryAndStorage_withoutExpiredFlag() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)
        manager.startSession("jwt-1", UserRole.ADMIN)

        manager.endSession()

        assertNull(manager.currentRole.value)
        assertNull(manager.token())
        assertNull(storage.load())
        assertFalse(manager.sessionExpired.value)
    }

    @Test
    fun expireSession_signsOutAndFlagsExpiry_whenTokenMatches() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)
        manager.startSession("jwt-1", UserRole.CBO_COLLECTION)

        manager.expireSession("jwt-1")

        assertNull(manager.currentRole.value)
        assertNull(storage.load())
        assertTrue(manager.sessionExpired.value)
    }

    @Test
    fun expireSession_ignoresA401ForAnOlderToken() {
        val manager = SessionManager(FakeTokenStorage())
        manager.startSession("new-jwt", UserRole.CBO_COLLECTION)

        manager.expireSession("old-jwt")

        assertEquals(UserRole.CBO_COLLECTION, manager.currentRole.value)
        assertEquals("new-jwt", manager.token())
        assertFalse(manager.sessionExpired.value)
    }

    @Test
    fun startSession_clearsExpiredFlag() {
        val manager = SessionManager(FakeTokenStorage())
        manager.startSession("jwt-1", UserRole.ADMIN)
        manager.expireSession("jwt-1")

        manager.startSession("jwt-2", UserRole.ADMIN)

        assertFalse(manager.sessionExpired.value)
    }

    @Test
    fun cboId_isPublished_persisted_andRestoredAfterProcessRestart() {
        val storage = FakeTokenStorage()
        SessionManager(storage).startSession("jwt-1", UserRole.CBO_COLLECTION, "cbo-7", "collector_one")

        val restarted = SessionManager(storage)

        assertEquals("cbo-7", restarted.cboId())
        assertEquals("cbo-7", storage.load()?.cboId)
    }

    @Test
    fun cboId_isNull_forUsersWithoutOne_andWhenSignedOut() {
        val manager = SessionManager(FakeTokenStorage())
        assertNull(manager.cboId())

        manager.startSession("jwt-1", UserRole.ADMIN)
        assertNull(manager.cboId())

        manager.startSession("jwt-2", UserRole.CBO_COLLECTION, "cbo-7")
        manager.endSession()
        assertNull(manager.cboId())
    }

    @Test
    fun aNewSession_replacesThePreviousUsersCbo() {
        val manager = SessionManager(FakeTokenStorage())
        manager.startSession("jwt-1", UserRole.CBO_COLLECTION, "cbo-7")

        manager.startSession("jwt-2", UserRole.ADMIN)

        assertNull(manager.cboId())
    }

    @Test
    fun username_isKept_persisted_andRestored() {
        val storage = FakeTokenStorage()
        SessionManager(storage).startSession("jwt-1", UserRole.VETTING, null, "vetting_test_user")

        assertEquals("vetting_test_user", SessionManager(storage).username())
        assertEquals("vetting_test_user", storage.load()?.username)
    }

    @Test
    fun username_isNullWhenSignedOut_orWhenNoneWasKept() {
        val manager = SessionManager(FakeTokenStorage())
        assertNull(manager.username())

        manager.startSession("jwt-1", UserRole.ADMIN)
        assertNull(manager.username())

        manager.startSession("jwt-2", UserRole.VETTING, null, "officer")
        manager.endSession()
        assertNull(manager.username())
    }
}
