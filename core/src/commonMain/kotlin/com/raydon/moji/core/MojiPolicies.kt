package com.raydon.moji.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object MojiJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun decodeBackupOrSnapshot(data: String): Pair<PlanSnapshot, PlanPreferencesArchive?> {
        runCatching { codec.decodeFromString<PlanBackupArchive>(data) }.getOrNull()?.let { archive ->
            if (archive.formatVersion > 1) throw SerializationException("unsupported backup")
            validate(archive.snapshot)
            return archive.snapshot to archive.preferences
        }
        val snapshot = codec.decodeFromString<PlanSnapshot>(data)
        validate(snapshot)
        return snapshot to null
    }

    fun encodeBackup(archive: PlanBackupArchive): String = codec.encodeToString(archive)

    fun validate(snapshot: PlanSnapshot) {
        if (snapshot.schemaVersion > PlanSnapshot.CURRENT_SCHEMA_VERSION) {
            throw SerializationException("unsupported snapshot")
        }
    }
}

object ChecklistPolicy {
    fun isVisible(
        scheduledStart: String,
        status: String,
        targetInstant: String,
        carriesOver: Boolean,
        timeZoneId: String,
    ): Boolean {
        if (status == "inProgress") return true
        val zone = TimeZone.of(timeZoneId)
        val plannedDay = Instant.parse(scheduledStart).toLocalDateTime(zone).date
        val targetDay = Instant.parse(targetInstant).toLocalDateTime(zone).date
        return when {
            plannedDay == targetDay -> true
            plannedDay > targetDay -> false
            else -> carriesOver && status != "completed" && status != "skipped"
        }
    }
}

object CountdownPolicy {
    fun dayCount(targetDate: String, now: String, includesToday: Boolean, timeZoneId: String): Int {
        val zone = TimeZone.of(timeZoneId)
        val start = Instant.parse(now).toLocalDateTime(zone).date
        val target = Instant.parse(targetDate).toLocalDateTime(zone).date
        val raw = start.daysUntil(target)
        return when {
            includesToday && raw > 0 -> raw + 1
            includesToday && raw < 0 -> raw - 1
            else -> raw
        }
    }

    fun nextOccurrenceDate(origin: LocalDate, rule: String, today: LocalDate): LocalDate {
        if (rule == "never" || origin >= today) return origin
        return when (rule) {
            "weekly" -> generateSequence(origin) { it.plus(7, DateTimeUnit.DAY) }
                .first { it >= today }
            "monthly" -> {
                var result = origin
                while (result < today) {
                    val firstNext = LocalDate(result.year, result.month, 1).plus(1, DateTimeUnit.MONTH)
                    val lastDay = firstNext.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).day
                    result = LocalDate(firstNext.year, firstNext.month, minOf(origin.day, lastDay))
                }
                result
            }
            "yearly" -> {
                var year = today.year
                while (true) {
                    val candidate = runCatching { LocalDate(year, origin.month, origin.day) }.getOrNull()
                    if (candidate != null && candidate >= today) return candidate
                    year += 1
                }
                origin
            }
            else -> origin
        }
    }
}

object PomodoroPolicy {
    fun nextBreak(completedFocusCount: Int, longBreakInterval: Int, longBreakEnabled: Boolean): String {
        val interval = maxOf(1, longBreakInterval)
        return if (
            longBreakEnabled && completedFocusCount > 0 && completedFocusCount % interval == 0
        ) "longBreak" else "shortBreak"
    }
}

object AnalyticsPolicy {
    fun overlapMinutes(startMillis: Long, endMillis: Long, dayStartMillis: Long, nextDayMillis: Long): Int {
        val overlap = maxOf(0, minOf(endMillis, nextDayMillis) - maxOf(startMillis, dayStartMillis))
        return if (overlap == 0L) 0 else maxOf(1, ((overlap + 59_999) / 60_000).toInt())
    }
}

object MojiCopy {
    const val APP_NAME = "Moji"
    const val PLAN = "计划"
    const val MEMO = "备忘"
    const val MOMENTS = "时刻"
    const val REVIEW = "回顾"
    const val NO_TITLE_MEMO = "无标题备忘"
}
