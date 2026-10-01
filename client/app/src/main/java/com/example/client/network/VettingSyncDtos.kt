package com.example.client.network

import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.VettingDecision

// Contract for `POST /api/vetting/sync` (backend #47, VettingController). Field names match the Room entity (camelCase
// JSON). The answer has exactly the shape of the CBO collection sync (one result per decision, in order), so it is read
// as the same [CboSyncResponse] / [CboSyncRecordResult] and handled by the same code.

data class VettingDecisionSyncDto(
    val id: String,
    val foodspaceRecordId: String,
    /** Serialised as the enum name: APPROVE, REJECT or FLAG, which is what the backend accepts. */
    val outcome: DecisionOutcome,
    val notes: String?,
    /** Informational only: the backend records the signed-in officer from the token, whatever is sent here. */
    val officerId: String,
    val decisionTimestamp: Long,
    val createdAt: Long,
    val updatedAt: Long
)

data class VettingSyncRequest(val records: List<VettingDecisionSyncDto>)

fun VettingDecision.toSyncDto() = VettingDecisionSyncDto(
    id = id,
    foodspaceRecordId = foodspaceRecordId,
    outcome = outcome,
    notes = notes,
    officerId = officerId,
    decisionTimestamp = decisionTimestamp,
    createdAt = createdAt,
    updatedAt = updatedAt
)
