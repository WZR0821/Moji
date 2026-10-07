package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MojiRepository(private val dao: SnapshotDao) {
    constructor(database: MojiDatabase) : this(database.snapshotDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    val loadError = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<PlanSnapshot> = dao.observe()
        .map { entity ->
            val decoded = entity?.let(::decode) ?: emptySnapshot()
            loadError.value = null
            decoded
        }
        .retryWhen { _, _ ->
            loadError.value = "本地数据暂时无法读取，请保留原数据并从备份恢复。"
            delay(5_000)
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySnapshot())

    suspend fun ensureInitialized() = mutex.withLock {
        if (dao.get() == null) save(emptySnapshot())
    }

    suspend fun freshSnapshot(): PlanSnapshot = withContext(Dispatchers.Default) {
        dao.get()?.let(::decode) ?: emptySnapshot()
    }

    // Used only to preserve unreadable original bytes before an explicit restore.
    suspend fun rawSnapshotJson(): String? = dao.get()?.json

    suspend fun replace(snapshot: PlanSnapshot) = withContext(Dispatchers.Default) { mutex.withLock {
        MojiJson.validate(snapshot)
        save(normalize(snapshot))
    } }

    suspend fun mutate(block: (PlanSnapshot) -> PlanSnapshot) = withContext(Dispatchers.Default) { mutex.withLock {
        val current = dao.get()?.let(::decode) ?: emptySnapshot()
        if (current.schemaVersion > PlanSnapshot.CURRENT_SCHEMA_VERSION) return@withLock
        save(normalize(block(current)))
    } }

    suspend fun savePlan(item: CheckInItem) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.filterNot { it.id == item.id } + item)
    }

    suspend fun saveCompletedPlan(item: CheckInItem, record: TimeRecord) = mutate { state ->
        val original = state.checkInItems.firstOrNull { it.id == item.id && it.kind == "planned" }
        val completed = original?.copy(title = item.title, category = item.category, note = item.note,
            status = "completed", actualStartDate = record.startDate, actualEndDate = record.endDate,
            completedAt = record.endDate, detailsConfigured = true)
        val updated = state.copy(
            records = state.records.filterNot { it.checkInItemID == item.id } + record,
            checkInItems = state.checkInItems.filterNot { it.id == item.id } + listOfNotNull(completed))
        if (completed != null) LocalRules.appendSuccessor(updated, completed, Instant.parse(record.endDate)) else updated
    }

    suspend fun togglePlan(id: String, now: Instant = Instant.now()) = mutate { state ->
        val original = state.checkInItems.firstOrNull { it.id == id } ?: return@mutate state
        if (original.status == "completed") {
            state.copy(
                checkInItems = state.checkInItems
                    .filterNot { it.generatedFromOccurrenceID == id && it.status == "planned" }
                    .map { if (it.id == id) it.copy(status = "planned", completedAt = null, actualStartDate = null, actualEndDate = null) else it },
                records = state.records.filterNot { it.checkInItemID == id },
            )
        } else {
            val completed = original.copy(status = "completed", completedAt = now.toString())
            val successor = LocalRules.successor(completed, now)
            state.copy(
                checkInItems = state.checkInItems.map { if (it.id == id) completed else it } +
                    listOfNotNull(successor).filter { next -> state.checkInItems.none { it.generatedFromOccurrenceID == id } },
            )
        }
    }

    suspend fun skipPlan(id: String) = mutate { state ->
        val original = state.checkInItems.firstOrNull { it.id == id } ?: return@mutate state
        if (original.status == "completed" || original.status == "inProgress") return@mutate state
        val now = Instant.now()
        val skipped = original.copy(status = "skipped", completedAt = now.toString())
        LocalRules.appendSuccessor(state.copy(checkInItems = state.checkInItems.map {
            if (it.id == id) skipped else it
        }), skipped, now)
    }

    suspend fun postponePlan(id: String, days: Long = 1) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.map { item ->
            if (item.id != id || item.status == "completed" || item.status == "inProgress") item else item.copy(
                scheduledStart = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).plusDays(days).toInstant().toString(),
                status = "planned", completedAt = null, actualStartDate = null, actualEndDate = null,
            )
        })
    }

    suspend fun deletePlan(id: String) = mutate { state ->
        state.copy(
            checkInItems = state.checkInItems.filterNot { it.id == id },
            records = state.records.filterNot { it.checkInItemID == id },
        )
    }

    suspend fun saveMemo(item: MemoItem) = mutate { state ->
        state.copy(memos = state.memos.filterNot { it.id == item.id } + item)
    }

    suspend fun toggleChecklistItem(memoId: String, itemId: String) = mutate { state ->
        state.copy(memos = state.memos.map { memo ->
            if (memo.id != memoId) memo else memo.copy(
                checklistItems = memo.checklistItems.map { item ->
                    if (item.id == itemId) item.copy(isCompleted = !item.isCompleted) else item
                }, updatedAt = Instant.now().toString(),
            )
        })
    }

    suspend fun deleteMemo(id: String) = mutate { it.copy(memos = it.memos.filterNot { memo -> memo.id == id }) }

    suspend fun toggleMemoPin(id: String) = mutate { state ->
        state.copy(memos = state.memos.map { if (it.id == id) it.copy(isPinned = !it.isPinned) else it })
    }

    suspend fun saveCountdown(item: CountdownEvent) = mutate { state ->
        state.copy(countdowns = state.countdowns.filterNot { it.id == item.id } + item)
    }

    suspend fun deleteCountdown(id: String) = mutate {
        it.copy(countdowns = it.countdowns.filterNot { event -> event.id == id })
    }

    suspend fun toggleCountdownPin(id: String) = mutate { state ->
        state.copy(countdowns = state.countdowns.map { if (it.id == id) it.copy(isPinned = !it.isPinned) else it })
    }

    /** Transfer the local timer's claim atomically so two timers cannot bank
     * the same minutes. Starting a linked plan mirrors iOS's 进行中 state. */
    suspend fun beginPomodoroFocus(linkedPlanId: String?, now: Instant = Instant.now()) = mutate { state ->
        val active = state.activeSession
        val elapsed = active?.let { java.time.Duration.between(Instant.parse(it.startedAt), now).seconds } ?: 0
        val banked = if (active != null && elapsed >= 60) listOf(TimeRecord(
            id = UUID.randomUUID().toString(), title = active.title, category = active.category,
            startDate = active.startedAt, endDate = now.toString(), note = "由桌面小组件记录",
            checkInItemID = active.checkInItemID)) else emptyList()
        state.copy(activeSession = null, records = state.records + banked,
            checkInItems = state.checkInItems.map { original ->
                val released = if (original.id == active?.checkInItemID && original.status == "inProgress")
                    original.copy(status = "planned", actualStartDate = null, actualEndDate = null) else original
                if (released.id == linkedPlanId && released.status == "planned" && released.isArchived != true)
                    released.copy(status = "inProgress", actualStartDate = now.toString()) else released
            })
    }

    suspend fun releasePomodoroFocus(linkedPlanId: String) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.map {
            if (it.id == linkedPlanId && it.status == "inProgress")
                it.copy(status = "planned", actualStartDate = null, actualEndDate = null) else it
        })
    }

    suspend fun recordFocus(
        title: String,
        category: String,
        linkedPlanId: String?,
        startedAt: Instant,
        endedAt: Instant,
        completePlan: Boolean,
    ) = mutate { state ->
        val record = TimeRecord(
            id = UUID.randomUUID().toString(),
            title = title,
            category = category,
            startDate = startedAt.toString(),
            endDate = endedAt.toString(),
            checkInItemID = linkedPlanId?.ifBlank { null },
        )
        val updated = state.copy(
            records = state.records + record,
            checkInItems = if (!linkedPlanId.isNullOrBlank()) {
                state.checkInItems.map {
                    if (it.id != linkedPlanId) it
                    else if (completePlan) it.copy(status = "completed", completedAt = endedAt.toString(), actualStartDate = startedAt.toString(), actualEndDate = endedAt.toString())
                    else if (it.status == "inProgress") it.copy(status = "planned", actualStartDate = null, actualEndDate = null)
                    else it
                }
            } else state.checkInItems,
        )
        val completed = updated.checkInItems.firstOrNull { it.id == linkedPlanId }
        if (completePlan && completed != null) LocalRules.appendSuccessor(updated, completed, endedAt) else updated
    }

    suspend fun deleteRecord(id: String) = mutate { state ->
        val record = state.records.firstOrNull { it.id == id } ?: return@mutate state
        val remaining = state.records.filterNot { it.id == id }
        val restore = record.checkInItemID != null &&
            state.checkInItems.any { it.id == record.checkInItemID && it.status == "completed" } &&
            remaining.none { it.checkInItemID == record.checkInItemID }
        state.copy(records = remaining, checkInItems = state.checkInItems
            .filterNot { restore && it.generatedFromOccurrenceID == record.checkInItemID && it.status == "planned" }
            .map { if (restore && it.id == record.checkInItemID) it.copy(status = "planned", completedAt = null, actualStartDate = null, actualEndDate = null) else it })
    }

    suspend fun saveRecord(record: TimeRecord) = mutate { state ->
        state.copy(records = state.records.filterNot { it.id == record.id } + record)
    }

    private suspend fun save(snapshot: PlanSnapshot) {
        dao.put(
            SnapshotEntity(
                json = MojiJson.codec.encodeToString(PlanSnapshot.serializer(), snapshot),
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun decode(entity: SnapshotEntity): PlanSnapshot =
        MojiJson.decodeBackupOrSnapshot(entity.json).first

    private fun normalize(snapshot: PlanSnapshot): PlanSnapshot = snapshot.copy(
        schemaVersion = PlanSnapshot.CURRENT_SCHEMA_VERSION,
        records = snapshot.records
            .filter { runCatching {
                val start = Instant.parse(it.startDate)
                val end = Instant.parse(it.endDate)
                end > start || (end == start && (it.scheduleKind ?: "exactTime") != "exactTime")
            }.getOrDefault(false) }
            .sortedByDescending { it.startDate },
        checkInItems = snapshot.checkInItems.map {
            if (it.kind == "completed") it.copy(kind = "completedLog") else it
        }.sortedBy { it.scheduledStart },
        countdowns = snapshot.countdowns.sortedWith(compareBy<CountdownEvent> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.createdAt })
            .mapIndexed { index, event -> event.copy(sortOrder = index) },
        memos = snapshot.memos.sortedWith(
            compareByDescending<MemoItem> { it.isPinned }.thenByDescending { it.updatedAt }
        ),
        lastUpdated = Instant.now().toString(),
    )


    companion object {
        fun emptySnapshot() = PlanSnapshot(lastUpdated = Instant.now().toString())
    }
}
