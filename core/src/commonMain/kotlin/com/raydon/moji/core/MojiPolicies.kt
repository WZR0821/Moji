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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

object MojiJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun decodeBackupOrSnapshot(data: String): Pair<PlanSnapshot, PlanPreferencesArchive?> {
        val root = codec.parseToJsonElement(data) as? JsonObject
            ?: throw SerializationException("备份必须是 JSON 对象")
        // Once an archive is recognized, decoding failure must never fall back
        // to a default-valued, empty legacy snapshot.
        val isArchive = listOf("snapshot", "formatVersion", "appIdentifier", "preferences").any { it in root }
        val snapshotObject = if (isArchive) root["snapshot"] as? JsonObject else root
        requireSnapshotShape(snapshotObject)
        val (rawSnapshot, preferences) = if (isArchive) {
            if (!root.keys.containsAll(listOf("formatVersion", "appIdentifier", "appVersion", "exportedAt"))) {
                throw SerializationException("备份信息不完整")
            }
            val archive = codec.decodeFromJsonElement<PlanBackupArchive>(root)
            if (archive.formatVersion != 1) throw SerializationException("不支持此备份版本，请更新 App")
            if (archive.appIdentifier != "com.raydon.moji") throw SerializationException("这不是 Moji 备份")
            Instant.parse(archive.exportedAt)
            archive.snapshot to archive.preferences
        } else {
            codec.decodeFromJsonElement<PlanSnapshot>(root) to null
        }
        val snapshot = rawSnapshot.copy(checkInItems = rawSnapshot.checkInItems.map {
            if (it.kind == "completed") it.copy(kind = "completedLog") else it
        })
        validate(snapshot)
        preferences?.let {
            it.customCategories?.let { json -> codec.decodeFromString<List<String>>(json) }
            it.quickPlanPresets?.let { json -> codec.decodeFromString<List<QuickPlanPresetArchive>>(json) }
            if (it.focusMinutes !in 1..180 || it.shortBreakMinutes !in 1..60 ||
                it.longBreakMinutes !in 1..90 || it.longBreakInterval !in 1..12 ||
                it.pomodoroRemaining < 0 || it.pomodoroAccumulated < 0 || it.pomodoroCompleted < 0 ||
                it.pomodoroPhase !in setOf("focus", "shortBreak", "longBreak")) {
                throw SerializationException("备份中的计时设置无效")
            }
        }
        return snapshot to preferences
    }

    private fun requireSnapshotShape(snapshot: JsonObject?) {
        if (snapshot == null || snapshot["records"] !is JsonArray ||
            snapshot["countdowns"] !is JsonArray || snapshot["lastUpdated"] !is JsonPrimitive ||
            listOf("checkInItems", "memos", "planTemplates", "weeklyGoals", "weeklyReflections").any { it in snapshot && snapshot[it] !is JsonArray }) {
            throw SerializationException("备份缺少必要的数据字段")
        }
    }

    fun portableSnapshot(snapshot: PlanSnapshot): PlanSnapshot = snapshot.copy(
        checkInItems = snapshot.checkInItems.map { it.copy(calendarEventIdentifier = null,
            kind = if (it.kind == "completed") "completedLog" else it.kind) },
        countdowns = snapshot.countdowns.map { it.copy(calendarEventIdentifier = null) },
    )

    fun encodeBackup(archive: PlanBackupArchive): String {
        validate(archive.snapshot)
        return codec.encodeToString(archive.copy(snapshot = portableSnapshot(archive.snapshot)))
    }

    fun validate(snapshot: PlanSnapshot) {
        if (snapshot.schemaVersion !in 1..PlanSnapshot.CURRENT_SCHEMA_VERSION) {
            throw SerializationException("不支持此数据版本，请更新 App")
        }
        fun unique(ids: List<String>) {
            if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) {
                throw SerializationException("备份包含重复或空白标识")
            }
        }
        fun choice(value: String?, allowed: Set<String>) {
            if (value != null && value !in allowed) throw SerializationException("备份包含未知类型：$value")
        }
        val schedules = setOf("exactTime", "allDay", "morning", "afternoon", "evening")
        Instant.parse(snapshot.lastUpdated)
        unique(snapshot.records.map { it.id })
        unique(snapshot.checkInItems.map { it.id })
        unique(snapshot.countdowns.map { it.id })
        unique(snapshot.memos.map { it.id })
        unique(snapshot.planTemplates.map { it.id })
        unique(snapshot.weeklyGoals.map { it.id })
        unique(snapshot.weeklyReflections.map { it.id })
        fun week(key: String) {
            val date = LocalDate.parse(key)
            if (PlanWorkflow.weekStart(date).toString() != key) throw SerializationException("周起始日期必须为周一")
        }
        snapshot.planTemplates.forEach {
            Instant.parse(it.createdAt); choice(it.scheduleKind, schedules)
            if (it.title.isBlank() || it.hour !in 0..23 || it.minute !in 0..59 || it.plannedMinutes < 1) throw SerializationException("计划模板无效")
        }
        snapshot.weeklyGoals.forEach {
            week(it.weekStart); Instant.parse(it.updatedAt)
            if ((it.targetCount == null && it.targetMinutes == null) || (it.targetCount != null && it.targetCount < 1) ||
                (it.targetMinutes != null && it.targetMinutes < 1)) throw SerializationException("周目标无效")
        }
        if (snapshot.weeklyGoals.map { it.weekStart to it.category }.distinct().size != snapshot.weeklyGoals.size ||
            snapshot.weeklyReflections.map { it.weekStart }.distinct().size != snapshot.weeklyReflections.size) throw SerializationException("重复的周目标或复盘")
        snapshot.weeklyReflections.forEach { week(it.weekStart); Instant.parse(it.updatedAt) }
        snapshot.records.forEach {
            choice(it.scheduleKind, schedules)
            val start = Instant.parse(it.startDate)
            val end = Instant.parse(it.endDate)
            if (end < start || ((it.scheduleKind ?: "exactTime") == "exactTime" && end == start)) {
                throw SerializationException("记录的结束时间无效：${it.title}")
            }
        }
        snapshot.checkInItems.forEach {
            Instant.parse(it.scheduledStart); Instant.parse(it.createdAt)
            listOfNotNull(it.actualStartDate, it.actualEndDate, it.completedAt).forEach(Instant::parse)
            choice(it.kind, setOf("planned", "completedLog", "completed"))
            choice(it.status, setOf("planned", "inProgress", "completed", "skipped"))
            choice(it.scheduleKind, schedules)
            choice(it.repeatRule, setOf("never", "daily", "weekdays", "weekly", "customWeekdays", "monthly"))
            if (it.repeatWeekdays.orEmpty().any { day -> day !in 1..7 }) throw SerializationException("重复星期无效")
        }
        snapshot.countdowns.forEach {
            Instant.parse(it.targetDate); Instant.parse(it.createdAt)
            choice(it.repeatRule, setOf("never", "weekly", "monthly", "yearly"))
        }
        snapshot.memos.forEach {
            Instant.parse(it.createdAt); Instant.parse(it.updatedAt)
            choice(it.mode, setOf("note", "checklist"))
            unique(it.checklistItems.map { item -> item.id })
        }
        snapshot.activeSession?.let { Instant.parse(it.startedAt) }
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
