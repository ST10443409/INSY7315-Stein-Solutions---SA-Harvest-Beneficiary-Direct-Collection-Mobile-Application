package com.example.client.sync

import com.example.client.auth.FakeTokenStorage
import com.example.client.auth.SessionManager
import com.example.client.auth.UserRole
import com.example.client.data.local.entity.CboCollectionEntity
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.UNKNOWN_OFFICER
import com.example.client.data.repository.CboCollectionRepositoryImpl
import com.example.client.data.repository.VettingRepositoryImpl
import com.example.client.network.ApiEnvelope
import com.example.client.network.CboSyncRecordResult
import com.example.client.network.CboSyncRequest
import com.example.client.network.CboSyncResponse
import com.example.client.network.SyncApiService
import com.example.client.network.SyncRequest
import com.example.client.network.SyncResponse
import com.example.client.network.VettingSyncRequest
import com.example.client.testing.FakeVettingDecisionDao
import com.example.client.testing.InMemoryCboCollectionDao
import com.example.client.ui.cbo.observeSubmissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * #70: unsynced records are only ever sent as the person who captured them.
 *
 * The scenario is a phone two people share. The first person captures records while offline and signs out; the second
 * signs in. The server attributes everything in a request to the token that sent it (a collection to that user's CBO and
 * submitter, a decision to that user as the officer), so before this fix the second person's sync would have sent the first
 * person's records under the second person's name. These tests run the real session, repositories and sync processors, with
 * the database and the network faked, and cover both forms.
 */
class SharedPhoneSyncTest {

    /** Accepts everything, and remembers exactly which record ids it was sent (by whom is the caller's token, i.e. who is signed in). */
    private class RecordingApi : SyncApiService {
        val collectionIdsSent = mutableListOf<String>()
        val decisionIdsSent = mutableListOf<String>()

        override suspend fun syncData(request: SyncRequest): Response<SyncResponse> = error("unused")

        override suspend fun syncCboCollections(request: CboSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            collectionIdsSent += request.records.map { it.id }
            return ok(request.records.map { it.id })
        }

        override suspend fun syncVettingDecisions(request: VettingSyncRequest): Response<ApiEnvelope<CboSyncResponse>> {
            decisionIdsSent += request.records.map { it.id }
            return ok(request.records.map { it.id })
        }

        private fun ok(ids: List<String>) =
            Response.success(ApiEnvelope(success = true, data = CboSyncResponse(ids.map { CboSyncRecordResult(it, true) })))
    }

    private val session = SessionManager(FakeTokenStorage())
    private val collections = InMemoryCboCollectionDao()
    private val decisions = FakeVettingDecisionDao()
    private val api = RecordingApi()
    private val collectionRepository = CboCollectionRepositoryImpl(collections)
    private val vettingRepository = VettingRepositoryImpl(decisions)
    private val collectionSync = CboSyncProcessor(collections, CboSyncProcessorTest.FakeProductLineDao(), api)
    private val decisionSync = VettingSyncProcessor(decisions, api)

    private fun signIn(username: String, role: UserRole) = session.startSession("jwt-$username", role, null, username)

    /** What BaseSyncWorker does for whoever is signed in: send for them, and only them. */
    private suspend fun runCollectionSync() = collectionSync.syncPending(session.currentUsername()!!)
    private suspend fun runDecisionSync() = decisionSync.syncPending(session.currentUsername()!!)

    // What Form1ViewModel does when it saves: the record carries the user who captured it.
    private suspend fun capture(id: String, author: String? = session.username()) = collectionRepository.save(
        CboCollectionEntity(
            id = id, cboId = "cbo", arrivalTime = "09:00", departureTime = null, donorName = "Donor $id",
            donorSigned = true, cboSigned = true, deliveryNote = "", noteAttached = false, collectNotes = "",
            shots = listOf(true), latitude = null, longitude = null, authorUsername = author
        ),
        emptyList()
    )

    // ── Form 1: collections ────────────────────────────────────────────────────────

    @Test
    fun aCollectionCapturedOffline_isNotSentUnderTheNextUsersAccount_butIsKept() = runTest {
        signIn("collector_one", UserRole.CBO_COLLECTION)
        capture("one-1")
        capture("one-2")
        session.endSession() // signs out with two records PENDING

        signIn("collector_two", UserRole.CBO_COLLECTION)
        capture("two-1")
        runCollectionSync()

        // Only the second collector's own record went out under the second collector's token.
        assertEquals(listOf("two-1"), api.collectionIdsSent)
        // The first collector's records are untouched: still there, still waiting, no retry used.
        val kept = collections.rows.value.filter { it.id.startsWith("one-") }
        assertEquals(listOf("one-1", "one-2"), kept.map { it.id }.sorted())
        assertTrue(kept.all { it.syncStatus == SyncStatus.PENDING && it.retryCount == 0 })
    }

    @Test
    fun theCollectorWhoCapturedThem_sendsThemWhenTheySignInAgain() = runTest {
        signIn("collector_one", UserRole.CBO_COLLECTION)
        capture("one-1")
        session.endSession()
        signIn("collector_two", UserRole.CBO_COLLECTION)
        runCollectionSync()
        assertTrue(api.collectionIdsSent.isEmpty()) // nothing of the second collector's, and the first's is not theirs to send
        session.endSession()

        signIn("collector_one", UserRole.CBO_COLLECTION)
        runCollectionSync()

        assertEquals(listOf("one-1"), api.collectionIdsSent)
        assertEquals(SyncStatus.SYNCED, collections.get("one-1").syncStatus)
    }

    @Test
    fun anAdminSigningInOnTheSamePhone_doesNotSendACollectorsRecords() = runTest {
        signIn("collector_one", UserRole.CBO_COLLECTION)
        capture("one-1")
        session.endSession()

        signIn("admin_test_user", UserRole.ADMIN)
        runCollectionSync()

        assertTrue(api.collectionIdsSent.isEmpty())
        assertEquals(SyncStatus.PENDING, collections.get("one-1").syncStatus)
    }

    @Test
    fun signingOut_deletesNothing() = runTest {
        signIn("collector_one", UserRole.CBO_COLLECTION)
        capture("one-1")
        val before = collections.rows.value

        session.endSession()

        assertEquals(before, collections.rows.value)
    }

    @Test
    fun theSameCollectorTypingTheirNameInAnotherCase_isStillTheSamePerson() = runTest {
        signIn("Collector_One", UserRole.CBO_COLLECTION) // the server accepts a username in any case at sign-in
        capture("one-1")
        session.endSession()

        signIn("collector_one", UserRole.CBO_COLLECTION)
        runCollectionSync()

        assertEquals(listOf("one-1"), api.collectionIdsSent)
    }

    @Test
    fun aRecordSavedBeforeAuthorsWereKept_isStillSentByWhoeverIsSignedIn() = runTest {
        capture("old-1", author = null) // saved by an earlier version of the app

        signIn("collector_two", UserRole.CBO_COLLECTION)
        runCollectionSync()

        assertEquals(listOf("old-1"), api.collectionIdsSent)
    }

    // ── Form 2: vetting decisions ──────────────────────────────────────────────────

    @Test
    fun aDecisionMadeOffline_isNotSentUnderTheNextOfficersAccount_butIsKept() = runTest {
        signIn("officer_one", UserRole.VETTING)
        val mine = vettingRepository.saveDecision("fs-1", DecisionOutcome.APPROVE, "fine", session.username()!!)
        session.endSession()

        signIn("officer_two", UserRole.VETTING)
        val theirs = vettingRepository.saveDecision("fs-2", DecisionOutcome.REJECT, null, session.username()!!)
        runDecisionSync()

        assertEquals(listOf(theirs.id), api.decisionIdsSent)
        val kept = decisions.get(mine.id)
        assertEquals(SyncStatus.PENDING, kept.syncStatus)
        assertEquals(0, kept.retryCount)
        assertEquals("fine", kept.notes) // what the first officer wrote is untouched
    }

    @Test
    fun theOfficerWhoMadeThem_sendsThemWhenTheySignInAgain() = runTest {
        signIn("officer_one", UserRole.VETTING)
        val mine = vettingRepository.saveDecision("fs-1", DecisionOutcome.APPROVE, null, session.username()!!)
        session.endSession()
        signIn("officer_two", UserRole.VETTING)
        runDecisionSync()
        assertTrue(api.decisionIdsSent.isEmpty())
        session.endSession()

        signIn("officer_one", UserRole.VETTING)
        runDecisionSync()

        assertEquals(listOf(mine.id), api.decisionIdsSent)
        assertEquals(SyncStatus.SYNCED, decisions.get(mine.id).syncStatus)
    }

    @Test
    fun anAdminSigningInOnTheSamePhone_doesNotSendAnOfficersDecisions() = runTest {
        signIn("officer_one", UserRole.VETTING)
        val mine = vettingRepository.saveDecision("fs-1", DecisionOutcome.FLAG, null, session.username()!!)
        session.endSession()

        signIn("admin_test_user", UserRole.ADMIN)
        runDecisionSync()

        assertTrue(api.decisionIdsSent.isEmpty())
        assertEquals(SyncStatus.PENDING, decisions.get(mine.id).syncStatus)
    }

    @Test
    fun aDecisionSavedBeforeTheOfficerWasKept_isStillSentByWhoeverIsSignedIn() = runTest {
        val old = vettingRepository.saveDecision("fs-1", DecisionOutcome.APPROVE, null, UNKNOWN_OFFICER)

        signIn("officer_two", UserRole.VETTING)
        runDecisionSync()

        assertEquals(listOf(old.id), api.decisionIdsSent)
    }

    // ── what each person sees ──────────────────────────────────────────────────────

    @Test
    fun eachCollectorSeesOnlyTheirOwnWork_andIsToldHowManyOthersAreWaiting() = runTest {
        signIn("collector_one", UserRole.CBO_COLLECTION)
        capture("one-1")
        capture("one-2")
        session.endSession()
        signIn("collector_two", UserRole.CBO_COLLECTION)
        capture("two-1")

        val second = collectionRepository.observeSubmissions("collector_two").first()
        assertEquals(listOf("two-1"), second.items.map { it.id })
        assertEquals(2, second.otherAccountsWaiting)
        assertEquals(1, second.unsent) // signing out now leaves only their own record behind

        val first = collectionRepository.observeSubmissions("collector_one").first()
        assertEquals(setOf("one-1", "one-2"), first.items.map { it.id }.toSet())
        assertEquals(1, first.otherAccountsWaiting)
    }

    @Test
    fun eachOfficerSeesOnlyTheirOwnQueue_andIsToldHowManyOthersAreWaiting() = runTest {
        signIn("officer_one", UserRole.VETTING)
        val mine = vettingRepository.saveDecision("fs-1", DecisionOutcome.APPROVE, null, session.username()!!)
        session.endSession()
        signIn("officer_two", UserRole.VETTING)
        val theirs = vettingRepository.saveDecision("fs-2", DecisionOutcome.APPROVE, null, session.username()!!)

        assertEquals(listOf(theirs.id), vettingRepository.observeDecisionsBy("officer_two").first().map { it.id })
        assertEquals(1, vettingRepository.observeWaitingForOthers("officer_two").first())
        assertEquals(listOf(mine.id), vettingRepository.observeDecisionsBy("officer_one").first().map { it.id })
    }
}
