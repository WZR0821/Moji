package com.raydon.moji.android.data

import com.raydon.moji.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalInteractionTest {
    @Test fun calendarPairsCompletedPlanWithItsExactRecordWithoutDuplicate() {
        val item = CheckInItem(id = "p", title = "计划", scheduledStart = "2026-10-06T00:00:00Z", createdAt = "2026-10-06T00:00:00Z", status = "completed", actualStartDate = "2026-10-07T01:00:00+00:00", actualEndDate = "2026-10-07T01:45:00Z")
        val exact = TimeRecord(id = "r", title = "计划", startDate = "2026-10-07T01:00:00Z", endDate = "2026-10-07T01:45:00Z", checkInItemID = "p")
        val extra = exact.copy(id = "extra", startDate = "2026-10-07T03:00:00Z", endDate = "2026-10-07T03:30:00Z")
        val calendar = LocalCalendar(PlanSnapshot(checkInItems = listOf(item), records = listOf(exact, extra)))
        val entries = calendar.entriesOn(LocalDate.parse("2026-10-07"), ZoneId.of("UTC"))
        assertEquals(2, entries.size)
        assertEquals("r", entries.first { it.plan != null }.record?.id)
        assertTrue(calendar.entriesOn(LocalDate.parse("2026-10-06"), ZoneId.of("UTC")).isEmpty())
    }
    @Test fun untimedCalendarRecordStillAppearsOnItsStartDay() {
        val record = TimeRecord(id = "untimed", title = "散步", startDate = "2026-10-07T00:00:00Z", endDate = "2026-10-07T00:00:00Z", scheduleKind = "allDay")
        val entries = LocalCalendar(PlanSnapshot(records = listOf(record))).entriesOn(LocalDate.parse("2026-10-07"), ZoneId.of("UTC"))
        assertEquals(1, entries.size)
        assertEquals("allDay", entries.single().schedule)
    }
    @Test fun unlimitedBackupRetentionSurvivesNormalization() {
        assertEquals(0, LocalRules.backupRetentionDays(0))
        assertEquals(180, LocalRules.backupRetentionDays(180))
        assertEquals(7, LocalRules.backupRetentionDays(-1))
        assertEquals(3650, LocalRules.backupRetentionDays(99999))
    }
    private val now = Instant.parse("2026-09-14T09:00:00Z")
    private val utc = ZoneId.of("UTC")
    private fun plan(id: String = "p", rule: String = "daily") = CheckInItem(
        id = id, title = "学习", scheduledStart = now.toString(), createdAt = now.toString(), repeatRule = rule)
    private fun record(id: String = "r", linked: String? = "p") = TimeRecord(
        id = id, title = "学习", startDate = "2026-09-14T23:30:00Z", endDate = "2026-09-15T00:30:00Z", checkInItemID = linked)
    private fun repository() = MojiRepository(FakeDao())

    @Test fun dailySuccessorAlwaysAdvancesEvenWhenCompletingEarly() {
        val future = plan().copy(scheduledStart = "2026-09-20T09:00:00Z")
        assertEquals("2026-09-21T09:00:00Z", LocalRules.successor(future, now, utc)!!.scheduledStart)
    }
    @Test fun emptyCustomWeekdaysDoesNotLoop() {
        assertNull(LocalRules.successor(plan(rule = "customWeekdays"), now, utc))
    }
    @Test fun customSundayUsesSwiftWeekdayNumber() {
        val next = LocalRules.successor(plan(rule = "customWeekdays").copy(repeatWeekdays = listOf(1)), now, utc)!!
        assertEquals("2026-09-20T09:00:00Z", next.scheduledStart)
    }
    @Test fun monthlyCatchUpKeepsOriginalDay() {
        val source = plan(rule = "monthly").copy(scheduledStart = "2026-01-31T09:00:00Z")
        assertEquals("2026-03-31T09:00:00Z", LocalRules.successor(source, Instant.parse("2026-03-01T09:00:00Z"), utc)!!.scheduledStart)
    }
    @Test fun skipCreatesOnlyOneSuccessor() = runBlocking {
        val repo = repository()
        repo.savePlan(plan())
        repo.skipPlan("p"); repo.skipPlan("p")
        val state = repo.freshSnapshot()
        assertEquals("skipped", state.checkInItems.first { it.id == "p" }.status)
        assertEquals(1, state.checkInItems.count { it.generatedFromOccurrenceID == "p" })
    }
    @Test fun focusCompletionCreatesSuccessorAndActualDates() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        repo.recordFocus("学习", "study", "p", now.minusSeconds(1500), now, true)
        val state = repo.freshSnapshot()
        assertEquals(2, state.checkInItems.size)
        assertEquals(now.toString(), state.checkInItems.first { it.id == "p" }.actualEndDate)
        assertEquals(1, state.records.size)
    }
    @Test fun partialFocusReturnsPlanToPendingWithoutCreatingASuccessor() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        repo.beginPomodoroFocus("p", now.minusSeconds(120))
        assertEquals("inProgress", repo.freshSnapshot().checkInItems.single().status)
        repo.recordFocus("学习", "study", "p", now.minusSeconds(120), now, false)
        val state = repo.freshSnapshot()
        assertEquals("planned", state.checkInItems.single().status)
        assertNull(state.checkInItems.single().actualStartDate)
        assertEquals(1, state.records.size)
    }
    @Test fun discardedFocusReleasesPlanWithoutARecord() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        repo.beginPomodoroFocus("p", now); repo.releasePomodoroFocus("p")
        assertEquals("planned", repo.freshSnapshot().checkInItems.single().status)
        assertTrue(repo.freshSnapshot().records.isEmpty())
    }
    @Test fun startingPomodoroBanksAndStopsThePreviousLocalTimer() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        repo.mutate { it.copy(activeSession = ActiveSession("s", "学习", startedAt = now.minusSeconds(120).toString(), checkInItemID = "p"),
            checkInItems = it.checkInItems.map { item -> item.copy(status = "inProgress") }) }
        repo.beginPomodoroFocus("p", now)
        val state = repo.freshSnapshot()
        assertNull(state.activeSession)
        assertEquals(1, state.records.size)
        assertEquals("inProgress", state.checkInItems.single().status)
        assertEquals(now.toString(), state.checkInItems.single().actualStartDate)
    }
    @Test fun convertingExistingPlanToCompletedKeepsRecurrence() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        repo.saveCompletedPlan(plan().copy(kind = "completed"), record())
        assertEquals("planned", repo.freshSnapshot().checkInItems.first { it.id == "p" }.kind)
        assertEquals(1, repo.freshSnapshot().checkInItems.count { it.generatedFromOccurrenceID == "p" })
    }
    @Test fun newCompletedLogHasNoShadowPlan() = runBlocking {
        val repo = repository()
        repo.saveCompletedPlan(plan().copy(kind = "completed"), record())
        assertTrue(repo.freshSnapshot().checkInItems.isEmpty())
        assertEquals(1, repo.freshSnapshot().records.size)
    }
    @Test fun undoPreservesSuccessorAlreadyInProgress() = runBlocking {
        val repo = repository(); repo.savePlan(plan()); repo.togglePlan("p", now)
        val next = repo.freshSnapshot().checkInItems.first { it.id != "p" }
        repo.savePlan(next.copy(status = "inProgress")); repo.togglePlan("p", now)
        assertEquals(2, repo.freshSnapshot().checkInItems.size)
        assertEquals("inProgress", repo.freshSnapshot().checkInItems.first { it.id == next.id }.status)
    }
    @Test fun deletingLastRecordRestoresCompletedPlanAndRemovesPendingSuccessor() = runBlocking {
        val repo = repository(); repo.savePlan(plan()); repo.saveCompletedPlan(plan(), record())
        repo.deleteRecord("r")
        assertEquals(listOf("planned"), repo.freshSnapshot().checkInItems.map { it.status })
        assertTrue(repo.freshSnapshot().records.isEmpty())
    }
    @Test fun deletingPartialFocusRecordDoesNotResetInProgressPlan() = runBlocking {
        val repo = repository(); repo.savePlan(plan().copy(status = "inProgress")); repo.saveRecord(record())
        repo.deleteRecord("r")
        assertEquals("inProgress", repo.freshSnapshot().checkInItems.single().status)
    }
    @Test fun deletingParentDoesNotDeleteNextOccurrence() = runBlocking {
        val repo = repository(); repo.savePlan(plan()); repo.togglePlan("p", now); repo.deletePlan("p")
        assertEquals(1, repo.freshSnapshot().checkInItems.size)
        assertEquals("p", repo.freshSnapshot().checkInItems.single().generatedFromOccurrenceID)
    }
    @Test fun crossMidnightStatisticsSplitMinutesBetweenDays() {
        val day = LocalDate.parse("2026-09-14")
        assertEquals(30L, LocalRules.minutes(listOf(record()), day, day.plusDays(1), utc))
        assertEquals(30L, LocalRules.minutes(listOf(record()), day.plusDays(1), day.plusDays(2), utc))
        assertEquals(60L, LocalRules.minutes(listOf(record()), day, day.plusDays(7), utc))
    }
    @Test fun nonPreciseAndInvalidRecordsDoNotInflateStatistics() {
        val day = LocalDate.parse("2026-09-14")
        assertEquals(0L, LocalRules.minutes(listOf(record().copy(scheduleKind = "allDay"), record().copy(endDate = "invalid")), day, day.plusDays(1), utc))
    }
    @Test fun restoringNonPreciseZeroDurationKeepsTheRecord() = runBlocking {
        val repo = repository()
        val allDay = record().copy(scheduleKind = "allDay", endDate = record().startDate)
        repo.replace(PlanSnapshot(records = listOf(allDay)))
        assertEquals(listOf(allDay), repo.freshSnapshot().records)
    }
    @Test fun corruptStoredJsonCannotBeOverwrittenByAMutation() = runBlocking {
        val dao = FakeDao()
        val corrupt = SnapshotEntity(json = "{broken", updatedAtMillis = 0)
        dao.put(corrupt)
        val repo = MojiRepository(dao)
        assertTrue(runCatching { repo.savePlan(plan()) }.isFailure)
        assertTrue(runCatching { repo.freshSnapshot() }.isFailure)
        assertEquals(corrupt, dao.get())
    }
    @Test fun freshSnapshotDoesNotDependOnTheObserverBeingReady() = runBlocking {
        val persisted = PlanSnapshot(checkInItems = listOf(plan()))
        val dao = object : SnapshotDao {
            override fun observe() = MutableStateFlow<SnapshotEntity?>(null)
            override suspend fun get() = SnapshotEntity(json = MojiJson.codec.encodeToString(PlanSnapshot.serializer(), persisted), updatedAtMillis = 0)
            override suspend fun put(entity: SnapshotEntity) { fail("Initialization must not replace an existing snapshot") }
        }
        val repo = MojiRepository(dao)
        repo.ensureInitialized()
        assertEquals(persisted, repo.freshSnapshot())
    }
    @Test fun invalidRestoreLeavesExistingSnapshotUntouched() = runBlocking {
        val repo = repository(); repo.savePlan(plan())
        val before = repo.freshSnapshot()
        assertTrue(runCatching { repo.replace(PlanSnapshot(records = listOf(record().copy(endDate = "bad")))) }.isFailure)
        assertEquals(before, repo.freshSnapshot())
    }
    @Test fun rapidChecklistTogglesUseTheLatestCommittedValue() = runBlocking {
        val repo = repository()
        repo.saveMemo(MemoItem("memo", mode = "checklist", checklistItems = listOf(MemoChecklistItem("item", "阅读")), createdAt = now.toString(), updatedAt = now.toString()))
        repo.toggleChecklistItem("memo", "item")
        repo.toggleChecklistItem("memo", "item")
        assertFalse(repo.freshSnapshot().memos.single().checklistItems.single().isCompleted)
    }
    private class FakeDao : SnapshotDao {
        private val data = MutableStateFlow<SnapshotEntity?>(null)
        override fun observe() = data
        override suspend fun get() = data.value
        override suspend fun put(entity: SnapshotEntity) { data.value = entity }
    }
}
