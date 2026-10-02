package com.example.client.data.repository

import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.VettingDecision
import kotlinx.coroutines.flow.Flow

/**
 * Local-first store for vetting decisions (Form 2). Saving only ever touches the local database: no network call
 * happens here, whatever the connectivity. Sending decisions to the backend is the sync worker's job (#46).
 */
interface VettingRepository {
    /**
     * Saves a decision on a beneficiary record locally as PENDING and returns it. A record can be decided more than
     * once (an officer changes their mind): every decision is kept, and the newest one is the current one.
     */
    suspend fun saveDecision(recordId: String, outcome: DecisionOutcome, notes: String?, officerId: String): VettingDecision

    /** Every decision on this device, whoever made it, newest first. Updates live. */
    fun observeDecisions(): Flow<List<VettingDecision>>

    /**
     * The decisions [officer] made, newest first: what an officer sees of their own sync queue on a phone other officers also
     * sign in to (#70). Updates live.
     */
    fun observeDecisionsBy(officer: String?): Flow<List<VettingDecision>>

    /**
     * How many decisions made by some other officer are still waiting on this phone for that officer to sign in and send
     * them (#70). Updates live.
     */
    fun observeWaitingForOthers(officer: String?): Flow<Int>

    /** The decisions on one record, newest first (the first is the current one). Updates live. */
    fun observeDecisionsFor(recordId: String): Flow<List<VettingDecision>>
}
