package com.raydon.moji.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MojiPoliciesTest {
    @Test
    fun futurePlanDoesNotAppearToday() {
        assertFalse(
            ChecklistPolicy.isVisible(
                scheduledStart = "2026-08-14T00:00:00Z",
                status = "planned",
                targetInstant = "2026-08-13T00:00:00Z",
                carriesOver = true,
                timeZoneId = "UTC",
            )
        )
    }

    @Test
    fun overduePlanFollowsCarryOverPreference() {
        assertTrue(
            ChecklistPolicy.isVisible(
                "2026-08-12T00:00:00Z", "planned", "2026-08-13T00:00:00Z", true, "UTC"
            )
        )
    }

    @Test
    fun longBreakUsesConfiguredInterval() {
        assertEquals("longBreak", PomodoroPolicy.nextBreak(4, 4, true))
        assertEquals("shortBreak", PomodoroPolicy.nextBreak(4, 4, false))
    }

    @Test
    fun countdownUsesCalendarDays() {
        assertEquals(2, CountdownPolicy.dayCount(
            "2026-08-15T00:00:00Z", "2026-08-13T12:00:00Z", false, "UTC"
        ))
    }

    @Test
    fun yearlyOccurrenceMovesForward() {
        assertEquals(
            LocalDate(2027, 2, 28),
            CountdownPolicy.nextOccurrenceDate(LocalDate(2020, 2, 28), "yearly", LocalDate(2026, 3, 1))
        )
    }

    @Test
    fun recordsAreSplitAtDayBoundary() {
        assertEquals(30, AnalyticsPolicy.overlapMinutes(0, 5_400_000, 3_600_000, 86_400_000))
    }
}
