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
        SessionManager(storage).startSession("jwt-1", UserRole.VETTING)

        val restarted = SessionManager(storage) // new process, same persisted storage

        assertEquals(UserRole.VETTING, restarted.currentRole.value)
        assertEquals("jwt-1", restarted.token())
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
        SessionManager(storage).startSession("jwt-1", UserRole.CBO_COLLECTION, "cbo-7")

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
}
