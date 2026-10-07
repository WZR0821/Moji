package com.raydon.moji.core

import kotlin.test.*
import kotlinx.datetime.LocalDate

class PlanWorkflowTest {
    private val now = "2026-10-06T00:00:00Z"
    private fun plan(id: String = "p", status: String = "planned") = CheckInItem(id, "阅读", scheduledStart = "2026-10-06T05:30:00Z", createdAt = now, status = status, plannedDurationEnabled = true, plannedMinutes = 45)
    private fun state(vararg plans: CheckInItem) = PlanSnapshot(checkInItems = plans.toList(), lastUpdated = now)
    private val memo = MemoItem("m", "出行", mode = "checklist", checklistItems = listOf(MemoChecklistItem("a", "车票", true), MemoChecklistItem("b", "充电器")), createdAt = now, updatedAt = now)
    private fun convert(state: PlanSnapshot, item: String? = null, id: String = "new") = PlanWorkflow.convertMemo(state, "m", item, id, now, now, "study")

    @Test fun convertsMemoWithoutChangingMemo() {
        val result = convert(PlanSnapshot(memos = listOf(memo), lastUpdated = now))
        assertEquals(listOf(memo), result.memos); assertEquals("出行", result.checkInItems.single().title)
        assertTrue(result.checkInItems.single().note.contains("☑ 车票"))
        assertEquals("m", result.checkInItems.single().sourceMemoID)
    }
    @Test fun convertsIndividualChecklistAndDeduplicates() {
        val source = PlanSnapshot(memos = listOf(memo), lastUpdated = now)
        val first = convert(source, "a")
        assertEquals("车票", first.checkInItems.single().title)
        assertEquals(first, convert(first, "a", "second"))
        assertEquals(2, convert(first, "b", "second").checkInItems.size)
    }
    @Test fun archivedConversionStillLinksOriginal() {
        val first = convert(PlanSnapshot(memos = listOf(memo), lastUpdated = now))
        val archived = first.copy(checkInItems = first.checkInItems.map { it.copy(isArchived = true) })
        assertEquals(archived, convert(archived))
    }
    @Test fun rejectsMissingOrEmptyConversion() {
        assertEquals(state(), convert(state()))
        val source = PlanSnapshot(memos = listOf(memo), lastUpdated = now)
        assertEquals(source, convert(source, "missing"))
        val empty = source.copy(memos = listOf(memo.copy(title = "", checklistItems = emptyList())))
        assertEquals(empty, convert(empty))
    }
    @Test fun copyHasNoHistoryOrSeriesOrSourceLink() {
        val source = plan(status = "completed").copy(repeatRule = "daily", seriesID = "series", generatedFromOccurrenceID = "old", sourceMemoID = "m", calendarEventIdentifier = "device", calendarSyncEnabled = true, actualEndDate = now, completedAt = now, isArchived = true)
        val result = PlanWorkflow.copied(source, "copy", now, now)
        assertEquals("planned", result.status); assertEquals("never", result.repeatRule)
        assertNull(result.seriesID); assertNull(result.actualEndDate); assertNull(result.completedAt)
        assertNull(result.sourceMemoID); assertNull(result.calendarEventIdentifier)
        assertEquals(false, result.isArchived); assertEquals(45, result.plannedMinutes)
    }
    @Test fun batchPreservesLocalTimeAndRejectsActivePlans() {
        val source = state(plan(), plan("active", "inProgress"), plan("done", "completed"))
        val (result, undo) = PlanWorkflow.batch(source, setOf("p", "active", "done"), "Asia/Tokyo", day = LocalDate.parse("2026-10-10"))
        assertEquals("2026-10-10T05:30:00Z", result.checkInItems.first().scheduledStart)
        assertEquals(1, undo.before.size); assertEquals(source, undo.apply(result))
    }
    @Test fun bulkArchiveRetainsRecordsAndUndoDoesNotRewindOtherData() {
        val source = state(plan())
        val (result, undo) = PlanWorkflow.batch(source, setOf("p"), "Asia/Tokyo", archived = true)
        val later = result.copy(memos = listOf(memo))
        assertEquals(listOf(memo), undo.apply(later).memos)
        assertEquals(source.checkInItems, undo.apply(later).checkInItems)
    }
    @Test fun undoNeverClobbersSubsequentEditOrFocus() {
        val (result, undo) = PlanWorkflow.batch(state(plan()), setOf("p"), "Asia/Tokyo", category = "work")
        val edited = result.copy(checkInItems = result.checkInItems.map { it.copy(title = "新标题") })
        assertEquals(edited, undo.apply(edited))
        val focused = result.copy(activeSession = ActiveSession("s", "阅读", startedAt = now, checkInItemID = "p"))
        assertEquals(focused, undo.apply(focused))
    }
    @Test fun pausedPomodoroPlanIsBlocked() {
        val source = state(plan())
        assertEquals(source, PlanWorkflow.batch(source, setOf("p"), "Asia/Tokyo", archived = true, blockedId = "p").first)
    }
    @Test fun undoAlsoProtectsPausedLinkedFocus() {
        val (result, undo) = PlanWorkflow.batch(state(plan()), setOf("p"), "Asia/Tokyo", category = "work")
        assertEquals(result, undo.apply(result, blockedId = "p"))
    }
    @Test fun untitledMemoUsesFirstNonblankLine() {
        val source = PlanSnapshot(memos = listOf(memo.copy(title = "", mode = "note", content = "\n  \n阅读一章\n整理笔记")), lastUpdated = now)
        assertEquals("阅读一章", convert(source).checkInItems.single().title)
    }
    @Test fun subminuteFocusIsRoundedOnceAfterAggregation() {
        val records = (1..4).map { TimeRecord("r$it", "阅读", startDate = "2026-10-06T00:00:00Z", endDate = "2026-10-06T00:00:45Z") }
        assertEquals(3, PlanWorkflow.summary(state().copy(records = records), "2026-10-04T15:00:00Z", "2026-10-11T15:00:00Z").actualMinutes)
    }
    @Test fun nextWeekMovesOriginalWithoutCreatingDuplicate() {
        val source = state(plan().copy(repeatRule = "daily", seriesID = "series"))
        val (result, undo) = PlanWorkflow.batch(source, setOf("p"), "Asia/Tokyo", nextWeekOf = LocalDate.parse("2026-10-06"))
        assertEquals(1, result.checkInItems.size); assertEquals("p", result.checkInItems.single().id)
        assertEquals("2026-10-13T05:30:00Z", result.checkInItems.single().scheduledStart)
        assertEquals("series", result.checkInItems.single().seriesID); assertEquals(source, undo.apply(result))
    }
    @Test fun checkedCompletionDoesNotInventFocusMinutes() {
        val summary = PlanWorkflow.summary(state(plan(status = "completed").copy(completedAt = now)), "2026-10-04T15:00:00Z", "2026-10-11T15:00:00Z")
        assertEquals(1, summary.completed.size); assertEquals(1, summary.untrackedCount); assertEquals(0, summary.actualMinutes)
        assertEquals(45, summary.estimatedMinutes)
    }
    @Test fun actualMinutesAreClippedAndFilteredByCategory() {
        val records = listOf(TimeRecord("r", "阅读", startDate = "2026-10-04T14:30:00Z", endDate = "2026-10-04T15:30:00Z"),
            TimeRecord("w", "工作", "work", "2026-10-05T00:00:00Z", "2026-10-05T01:00:00Z"),
            TimeRecord("untimed", "全天", startDate = now, endDate = now, scheduleKind = "allDay"))
        val summary = PlanWorkflow.summary(state().copy(records = records), "2026-10-04T15:00:00Z", "2026-10-11T15:00:00Z", "study")
        assertEquals(30, summary.actualMinutes)
    }
    @Test fun archiveRoundTripIncludesGoalsReflectionAndTemplates() {
        val source = state(plan()).copy(planTemplates = listOf(PlanTemplate("t", "阅读", "study", createdAt = now)),
            weeklyGoals = listOf(WeeklyGoal("g", "2026-10-05", targetCount = 4, targetMinutes = 120, updatedAt = now)),
            weeklyReflections = listOf(WeeklyReflection("f", "2026-10-05", "保持节奏", "读完一章", now)))
        val backup = MojiJson.encodeBackup(PlanBackupArchive(appVersion = "1.5.0", exportedAt = now, snapshot = source))
        assertEquals(source, MojiJson.decodeBackupOrSnapshot(backup).first)
    }
    @Test fun oldSchemaDefaultsNewCollectionsToEmpty() {
        val legacy = """{"schemaVersion":12,"records":[],"countdowns":[],"memos":[],"lastUpdated":"$now"}"""
        val restored = MojiJson.decodeBackupOrSnapshot(legacy).first
        assertTrue(restored.planTemplates.isEmpty()); assertTrue(restored.weeklyGoals.isEmpty()); assertTrue(restored.weeklyReflections.isEmpty())
    }
    @Test fun invalidAndDuplicateGoalsAreRejected() {
        val goal = WeeklyGoal("g", "2026-10-05", targetCount = 0, updatedAt = now)
        assertFails { MojiJson.validate(state().copy(weeklyGoals = listOf(goal))) }
        assertFails { MojiJson.validate(state().copy(weeklyGoals = listOf(goal.copy(targetCount = 1, weekStart = "2026-10-06")))) }
        assertFails { MojiJson.validate(state().copy(weeklyGoals = listOf(goal.copy(targetCount = 1), goal.copy(id = "g2", targetCount = 2)))) }
    }
    @Test fun mondayIsStableAcrossYearBoundary() {
        assertEquals("2025-12-29", PlanWorkflow.weekStart(LocalDate.parse("2026-01-01")).toString())
    }
}
