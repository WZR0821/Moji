package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PlanQuickChangeTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun plan() = CheckInItem(id = "p", title = "阅读", scheduledStart = "2026-10-12T05:30:00Z", createdAt = "2026-10-10T00:00:00Z",
        category = "study", note = "保留说明", plannedMinutes = 45, plannedDurationEnabled = true, scheduleKind = "exactTime",
        repeatRule = "weekly", reminderMinutesBefore = 15, status = "inProgress")

    @Test fun contentPatchesLatestPlanWithoutResettingLiveState() {
        val latest = plan().copy(status = "completed", completedAt = "2026-10-12T06:00:00Z", actualStartDate = "2026-10-12T05:30:00Z")
        val changed = PlanQuickChange.Content(" 新标题 ", " 新说明 ").applyTo(latest, zone)!!
        assertEquals(latest.copy(title = "新标题", note = "新说明", detailsConfigured = true), changed)
    }
    @Test fun datePreservesWallTimeAndOtherSettings() {
        val original = plan()
        val changed = PlanQuickChange.Date(LocalDate.parse("2026-10-20")).applyTo(original, zone)!!
        assertEquals(original.copy(scheduledStart = "2026-10-20T05:30:00Z", detailsConfigured = true), changed)
    }
    @Test fun dateHandlesSpringForwardWithoutLosingMinutes() {
        val original = plan().copy(scheduledStart = "2026-03-07T07:30:00Z")
        val changed = PlanQuickChange.Date(LocalDate.parse("2026-03-08")).applyTo(original, ZoneId.of("America/New_York"))!!
        assertEquals("2026-03-08T07:30:00Z", changed.scheduledStart)
    }
    @Test fun allDayAndDurationAreIndependent() {
        val changed = PlanQuickChange.Schedule("allDay", LocalTime.of(14, 30), null).applyTo(plan(), zone)!!
        assertEquals("2026-10-11T15:00:00Z", changed.scheduledStart)
        assertEquals(false, changed.plannedDurationEnabled)
        assertEquals(45, changed.plannedMinutes)
        assertEquals("weekly", changed.repeatRule)
    }
    @Test fun unrelatedEditDoesNotEnableLegacyEstimate() {
        val original = plan().copy(plannedDurationEnabled = null, detailsConfigured = false)
        val changed = PlanQuickChange.Category("work").applyTo(original, zone)!!
        assertEquals(false, changed.plannedDurationEnabled)
        assertEquals(original.scheduledStart, changed.scheduledStart)
    }
    @Test fun customWeekdaysRequireValidSelection() {
        assertNull(PlanQuickChange.Recurrence("customWeekdays", setOf(0, 8)).applyTo(plan()))
        assertEquals(listOf(2, 6), PlanQuickChange.Recurrence("customWeekdays", setOf(6, 2, 8)).applyTo(plan())!!.repeatWeekdays)
        assertTrue(PlanQuickChange.Recurrence("never", setOf(2)).applyTo(plan())!!.repeatWeekdays!!.isEmpty())
    }
    @Test fun invalidContentCategoryOrRecordCannotBeQuickEdited() {
        assertNull(PlanQuickChange.Content(" \n ", "note").applyTo(plan()))
        assertNull(PlanQuickChange.Category("custom:").applyTo(plan()))
        assertNull(PlanQuickChange.Content("title", "note").applyTo(plan().copy(kind = "completedLog")))
    }
    @Test fun reminderAndEstimateAreBounded() {
        assertNull(PlanQuickChange.Reminder(null).applyTo(plan())!!.reminderMinutesBefore)
        assertEquals(10080, PlanQuickChange.Reminder(Int.MAX_VALUE).applyTo(plan())!!.reminderMinutesBefore)
        assertEquals(1440, PlanQuickChange.Schedule("exactTime", LocalTime.NOON, Int.MAX_VALUE).applyTo(plan())!!.plannedMinutes)
    }
}
