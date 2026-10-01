package com.example.client.testing

import com.example.client.auth.Session
import com.example.client.auth.TokenStorage
import com.example.client.data.local.dao.VettingDecisionDao
import com.example.client.data.local.entity.DecisionOutcome
import com.example.client.data.local.entity.FoodspaceBeneficiaryRecord
import com.example.client.data.local.entity.SyncStatus
import com.example.client.data.local.entity.VettingDecision
import com.example.client.data.repository.AdminSyncStatusRepository
import com.example.client.data.repository.FormSyncCounts
import com.example.client.data.repository.RecordsMeta
import com.example.client.data.repository.RecordsMetaStore
import com.example.client.data.repository.RefreshOutcome
import com.example.client.data.repository.SyncStatusResult
import com.example.client.data.repository.SyncStatusSnapshot
import com.example.client.data.repository.VettingRecordsRepository
import com.example.client.data.repository.VettingRepository
import com.example.client.sync.VettingSyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

// Shared by src/test and src/androidTest (see the sharedTest source set in app/build.gradle.kts).

/** A fully filled-in beneficiary record, so a test can assert on any field. */
fun sampleRecord(
    id: String = "fs-1001",
    legalName: String = "Sizanani Community Feeding NPO",
    province: String = "Gauteng",
    contactName: String = "Nomsa Dlamini"
) = FoodspaceBeneficiaryRecord(
    id = id,
    legalName = legalName,
    contactName = contactName,
    contactEmail = "nomsa@sizanani.example.org",
    contactPhone = "+27 82 000 0001",
    website = "https://sizanani.example.org",
    address = "12 Vilakazi Street",
    address2 = "Orlando West",
    province = province,
    what3words = "filled.count.soap",
    coreBusiness = "Feeding",
    targetPopulation = listOf("Children", "Elderly"),
    programmes = "Feeding scheme, after-school homework club",
    distributionChannel = "Direct",
    fullTimeFemales = 3,
    fullTimeMales = 1,
    volunteers = 12,
    registeredNpo = true,
    npoCertificate = "https://files.example.org/npo-123.pdf",
    registeredDsd = false,
    pboCertificate = null,
    race = listOf("African"),
    gender = listOf("Female", "Male"),
    ageGroups = listOf("0–5", "60+"),
    feedingFrequency = "Daily",
    totalServed = 220,
    femalesServed = 130,
    malesServed = 90,
    africanServed = 200,
    colouredServed = 15,
    indianServed = 3,
    whiteServed = 2,
    relianceOnSaHarvest = "High",
    transportCapacity = "Own vehicle",
    mealsProvided = listOf("Lunch", "Dinner"),
    daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri"),
    lastDateFed = 1_790_000_000_000L,
    foodStorage = listOf("Dry", "Chilled"),
    kitchenImages = "https://files.example.org/kitchen-1.jpg",
    kitchenCleanliness = true,
    accessToWater = true,
    toilets = true,
    pestFree = false,
    infrastructureChecks = listOf("Roofing", "Gas safety"),
    easeOfAccess = true,
    parkingSecurity = false,
    policeProximity = "Orlando SAPS, 1.2 km",
    additionalComments = "Well organised; strong volunteer base.",
    proposalWriting = null,
    digitalCapabilities = "Uses WhatsApp and a shared spreadsheet",
    facilityPhotos = null,
    hasSla = true,
    hasConsent = true,
    hasPolicy = false,
    certificates = "https://files.example.org/certs.zip"
)

fun sampleDecision(
    recordId: String = "fs-1001",
    outcome: DecisionOutcome = DecisionOutcome.APPROVE,
    at: Long = 1_000L,
    notes: String? = null,
    officer: String = "vetting_test_user"
) = VettingDecision(
    id = UUID.randomUUID().toString(),
    foodspaceRecordId = recordId,
    outcome = outcome,
    notes = notes,
    officerId = officer,
    decisionTimestamp = at,
    syncStatus = SyncStatus.PENDING,
    createdAt = at,
    updatedAt = at
)

class FakeVettingRecordsRepository(initial: List<FoodspaceBeneficiaryRecord> = emptyList()) : VettingRecordsRepository {
    val records = MutableStateFlow(initial)
    private val _meta = MutableStateFlow<RecordsMeta?>(null)

    /** What the next [refresh] returns; when it is [RefreshOutcome.UPDATED] the cache becomes [onRefresh]. */
    var outcome: RefreshOutcome = RefreshOutcome.UPDATED
    var onRefresh: List<FoodspaceBeneficiaryRecord>? = null
    var refreshCalls = 0

    /** When set, [refresh] waits for it, so a test can look at the screen while a sync is running. */
    var gate: CompletableDeferred<Unit>? = null

    override val meta: StateFlow<RecordsMeta?> = _meta.asStateFlow()

    fun setMeta(meta: RecordsMeta?) {
        _meta.value = meta
    }

    override fun observeRecords(): Flow<List<FoodspaceBeneficiaryRecord>> = records
    override fun observeRecord(id: String): Flow<FoodspaceBeneficiaryRecord?> = records.map { l -> l.firstOrNull { it.id == id } }

    override suspend fun refresh(now: () -> Long): RefreshOutcome {
        refreshCalls++
        gate?.await()
        if (outcome == RefreshOutcome.UPDATED) onRefresh?.let { records.value = it }
        return outcome
    }
}

class FakeVettingRepository : VettingRepository {
    val decisions = MutableStateFlow<List<VettingDecision>>(emptyList())
    var failWith: Exception? = null
    private var clock = 1_000L

    override suspend fun saveDecision(recordId: String, outcome: DecisionOutcome, notes: String?, officerId: String): VettingDecision {
        failWith?.let { throw it }
        val decision = sampleDecision(recordId, outcome, at = clock++, notes = notes?.trim()?.takeIf { it.isNotEmpty() }, officer = officerId)
        decisions.value = (listOf(decision) + decisions.value).sortedByDescending { it.decisionTimestamp }
        return decision
    }

    override fun observeDecisions(): Flow<List<VettingDecision>> = decisions
    override fun observeDecisionsFor(recordId: String): Flow<List<VettingDecision>> =
        decisions.map { l -> l.filter { it.foodspaceRecordId == recordId } }
}

class InMemoryRecordsMetaStore(var stored: RecordsMeta? = null) : RecordsMetaStore {
    override fun load(): RecordsMeta? = stored
    override fun save(meta: RecordsMeta) {
        stored = meta
    }
}

class InMemoryTokenStorage(private var session: Session? = null) : TokenStorage {
    override fun load(): Session? = session
    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

/** An in-memory [VettingDecisionDao] with the same semantics as the real queries, for tests of anything that uses the DAO. */
class FakeVettingDecisionDao : VettingDecisionDao {
    val rows = MutableStateFlow<List<VettingDecision>>(emptyList())

    fun get(id: String) = rows.value.first { it.id == id }

    override suspend fun insert(decision: VettingDecision) {
        rows.value = rows.value.filterNot { it.id == decision.id } + decision
    }

    override suspend fun update(decision: VettingDecision) = insert(decision)
    override fun getBySyncStatus(status: SyncStatus): Flow<List<VettingDecision>> = rows.map { l -> l.filter { it.syncStatus == status } }
    override fun getAll(): Flow<List<VettingDecision>> = rows

    override fun observeAllNewestFirst(): Flow<List<VettingDecision>> =
        rows.map { l -> l.sortedWith(compareByDescending<VettingDecision> { it.decisionTimestamp }.thenByDescending { it.createdAt }) }

    override fun observeForRecord(recordId: String): Flow<List<VettingDecision>> =
        observeAllNewestFirst().map { l -> l.filter { it.foodspaceRecordId == recordId } }

    override suspend fun getSyncable(maxRetries: Int): List<VettingDecision> = rows.value
        .filter { it.syncStatus == SyncStatus.PENDING || (it.syncStatus == SyncStatus.FAILED && it.retryCount < maxRetries) }
        .sortedWith(compareBy({ it.decisionTimestamp }, { it.createdAt }))

    override suspend fun markSynced(ids: List<String>, now: Long) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(syncStatus = SyncStatus.SYNCED, syncErrorCode = null, updatedAt = now) else it }
    }

    override suspend fun markFailed(ids: List<String>, errorCode: String?, now: Long) {
        rows.value = rows.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = it.retryCount + 1, syncErrorCode = errorCode, updatedAt = now) else it
        }
    }

    override suspend fun markRejected(ids: List<String>, errorCode: String?, maxRetries: Int, now: Long) {
        rows.value = rows.value.map {
            if (it.id in ids) it.copy(syncStatus = SyncStatus.FAILED, retryCount = maxRetries, syncErrorCode = errorCode, updatedAt = now) else it
        }
    }
}

class FakeVettingSyncTrigger : VettingSyncTrigger {
    var calls = 0
    override fun syncVettingDecisionsNow() {
        calls++
    }
}

fun sampleCounts(
    waiting: Int = 0, retrying: Int = 0, needsAttention: Int = 0, forwarded: Int = 0, duplicates: Int = 0, superseded: Int = 0
) = FormSyncCounts(
    total = waiting + retrying + needsAttention + forwarded + duplicates + superseded,
    waiting = waiting, retrying = retrying, needsAttention = needsAttention,
    forwarded = forwarded, duplicates = duplicates, superseded = superseded
)

fun sampleSyncSnapshot(
    form1: FormSyncCounts = sampleCounts(waiting = 2, retrying = 1, needsAttention = 3, forwarded = 10, duplicates = 4),
    form2: FormSyncCounts = sampleCounts(waiting = 1, needsAttention = 0, forwarded = 7, superseded = 2),
    takenAtMillis: Long = 1_700_000_000_000L
) = SyncStatusSnapshot(form1, form2, takenAtMillis)

class FakeAdminSyncStatusRepository : AdminSyncStatusRepository {
    /** What the next [load] returns. */
    var result: SyncStatusResult = SyncStatusResult.Loaded(sampleSyncSnapshot())
    var loadCalls = 0

    /** When set, [load] waits for it, so a test can look at the screen while a refresh is running. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load(now: () -> Long): SyncStatusResult {
        loadCalls++
        gate?.await()
        return result
    }
}
