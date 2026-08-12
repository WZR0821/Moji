package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MojiRepository(private val database: MojiDatabase) {
    private val dao = database.snapshotDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    val snapshot: StateFlow<PlanSnapshot> = dao.observe()
        .map { entity -> entity?.let(::decode) ?: emptySnapshot() }
        .stateIn(scope, SharingStarted.Eagerly, emptySnapshot())

    suspend fun ensureInitialized() {
        if (dao.get() == null) save(emptySnapshot())
    }

    suspend fun replace(snapshot: PlanSnapshot) = mutex.withLock {
        MojiJson.validate(snapshot)
        save(normalize(snapshot))
    }

    suspend fun mutate(block: (PlanSnapshot) -> PlanSnapshot) = mutex.withLock {
        val current = dao.get()?.let(::decode) ?: emptySnapshot()
        if (current.schemaVersion > PlanSnapshot.CURRENT_SCHEMA_VERSION) return@withLock
        save(normalize(block(current)))
    }

    suspend fun savePlan(item: CheckInItem) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.filterNot { it.id == item.id } + item)
    }

    suspend fun togglePlan(id: String, now: Instant = Instant.now()) = mutate { state ->
        val original = state.checkInItems.firstOrNull { it.id == id } ?: return@mutate state
        if (original.status == "completed") {
            state.copy(
                checkInItems = state.checkInItems
                    .filterNot { it.generatedFromOccurrenceID == id }
                    .map { if (it.id == id) it.copy(status = "planned", completedAt = null) else it },
                records = state.records.filterNot { it.checkInItemID == id },
            )
        } else {
            val completed = original.copy(status = "completed", completedAt = now.toString())
            val successor = recurringSuccessor(completed, now)
            state.copy(
                checkInItems = state.checkInItems.map { if (it.id == id) completed else it } +
                    listOfNotNull(successor).filter { next -> state.checkInItems.none { it.generatedFromOccurrenceID == id } },
            )
        }
    }

    suspend fun skipPlan(id: String) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.map {
            if (it.id == id) it.copy(status = "skipped") else it
        })
    }

    suspend fun postponePlan(id: String, days: Long = 1) = mutate { state ->
        state.copy(checkInItems = state.checkInItems.map { item ->
            if (item.id != id) item else item.copy(
                scheduledStart = Instant.parse(item.scheduledStart).plus(days, ChronoUnit.DAYS).toString(),
                status = "planned",
            )
        })
    }

    suspend fun deletePlan(id: String) = mutate { state ->
        state.copy(
            checkInItems = state.checkInItems.filterNot { it.id == id || it.generatedFromOccurrenceID == id },
            records = state.records.filterNot { it.checkInItemID == id },
        )
    }

    suspend fun saveMemo(item: MemoItem) = mutate { state ->
        state.copy(memos = state.memos.filterNot { it.id == item.id } + item)
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
        state.copy(
            records = state.records + record,
            checkInItems = if (completePlan && !linkedPlanId.isNullOrBlank()) {
                state.checkInItems.map {
                    if (it.id == linkedPlanId) it.copy(status = "completed", completedAt = endedAt.toString()) else it
                }
            } else state.checkInItems,
        )
    }

    suspend fun deleteRecord(id: String) = mutate { it.copy(records = it.records.filterNot { record -> record.id == id }) }

    private suspend fun save(snapshot: PlanSnapshot) {
        dao.put(
            SnapshotEntity(
                json = MojiJson.codec.encodeToString(PlanSnapshot.serializer(), snapshot),
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun decode(entity: SnapshotEntity): PlanSnapshot = runCatching {
        MojiJson.codec.decodeFromString(PlanSnapshot.serializer(), entity.json)
    }.getOrElse { emptySnapshot() }

    private fun normalize(snapshot: PlanSnapshot): PlanSnapshot = snapshot.copy(
        schemaVersion = PlanSnapshot.CURRENT_SCHEMA_VERSION,
        records = snapshot.records
            .filter { runCatching { Instant.parse(it.endDate) > Instant.parse(it.startDate) }.getOrDefault(false) }
            .sortedByDescending { it.startDate },
        checkInItems = snapshot.checkInItems.sortedBy { it.scheduledStart },
        countdowns = snapshot.countdowns.sortedWith(
            compareByDescending<CountdownEvent> { it.isPinned }
                .thenBy { it.sortOrder ?: Int.MAX_VALUE }
                .thenBy { it.targetDate }
        ),
        memos = snapshot.memos.sortedWith(
            compareByDescending<MemoItem> { it.isPinned }.thenByDescending { it.updatedAt }
        ),
        lastUpdated = Instant.now().toString(),
    )

    private fun recurringSuccessor(item: CheckInItem, now: Instant): CheckInItem? {
        val rule = item.repeatRule ?: "never"
        if (rule == "never" || item.kind != "planned") return null
        val zone = ZoneId.systemDefault()
        val source = ZonedDateTime.ofInstant(Instant.parse(item.scheduledStart), zone)
        var candidate = source
        when (rule) {
            "daily" -> while (!candidate.toLocalDate().isAfter(ZonedDateTime.ofInstant(now, zone).toLocalDate())) candidate = candidate.plusDays(1)
            "weekdays" -> do candidate = candidate.plusDays(1) while (candidate.dayOfWeek.value > 5 || !candidate.toLocalDate().isAfter(ZonedDateTime.ofInstant(now, zone).toLocalDate()))
            "weekly" -> while (!candidate.toLocalDate().isAfter(ZonedDateTime.ofInstant(now, zone).toLocalDate())) candidate = candidate.plusWeeks(1)
            "monthly" -> while (!candidate.toLocalDate().isAfter(ZonedDateTime.ofInstant(now, zone).toLocalDate())) candidate = candidate.plusMonths(1)
            "customWeekdays" -> {
                val selected = (item.repeatWeekdays ?: emptyList()).map { if (it == 1) 7 else it - 1 }.toSet()
                do candidate = candidate.plusDays(1) while (
                    candidate.dayOfWeek.value !in selected || !candidate.toLocalDate().isAfter(ZonedDateTime.ofInstant(now, zone).toLocalDate())
                )
            }
            else -> return null
        }
        return item.copy(
            id = UUID.randomUUID().toString(),
            scheduledStart = candidate.toInstant().toString(),
            status = "planned",
            completedAt = null,
            actualStartDate = null,
            actualEndDate = null,
            createdAt = now.toString(),
            seriesID = item.seriesID ?: item.id,
            generatedFromOccurrenceID = item.id,
            calendarEventIdentifier = null,
        )
    }

    companion object {
        fun emptySnapshot() = PlanSnapshot(lastUpdated = Instant.now().toString())
    }
}
