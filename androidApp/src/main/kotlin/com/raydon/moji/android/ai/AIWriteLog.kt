package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanWorkflowUndo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Ported from Moji/AI/AIWriteLog.swift.

/**
 * One write Moji made from a draft the user accepted: the plans it added and
 * the plans it changed, kept so it can be taken back from 今天的整理 later in
 * the day, not only from the undo bar right after.
 */
data class AIWriteEntry(
    val id: String,
    /** Seconds since 1970. */
    val at: Double,
    val summary: String,
    val added: List<CheckInItem>,
    val before: List<CheckInItem>,
    val after: List<CheckInItem>,
    val undone: Boolean = false,
) {
    val undo: PlanDraftUndo get() = PlanDraftUndo(added, PlanWorkflowUndo(before, after))

    val total: Int get() = added.size + before.size

    val instant: Instant get() = Instant.ofEpochMilli((at * 1000).toLong())

    fun day(zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()
}

/** The words for 留痕: where a plan came from and what one write did. Pure, so both ends say exactly the same. */
object AIWriteText {
    /** 拆自「准备季度汇报」 */
    fun splitOrigin(title: String): String = "拆自「${AIDailyBrief.shortTitle(title)}」"
    const val textOrigin = "从文字整理"
    const val imageOrigin = "从截图整理"
    const val shareOrigin = "从分享整理"
    const val nextWeekOrigin = "按上周记录拟"
    fun preparationOrigin(title: String): String = "为「${AIDailyBrief.shortTitle(title)}」拟"

    /** The vermilion part of a row's small line: 墨记拆自「准备季度汇报」. */
    fun mark(origin: String): String = "墨记$origin"

    fun breakdown(count: Int, title: String, archived: Boolean): String =
        "拆成 $count 步：「${AIDailyBrief.shortTitle(title)}」" + if (archived) "，原计划已归档" else ""

    fun entrySplit(count: Int, original: String): String = "拆成 $count 条：「${AIDailyBrief.shortTitle(original)}」"

    /** What a suggestion changed, in the order 日期、时间、时长、类型、重复、标题. */
    fun changedFields(old: CheckInItem, new: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val fields = mutableListOf<String>()
        val oldStart = AIDrafting.instant(old.scheduledStart)?.atZone(zone)
        val newStart = AIDrafting.instant(new.scheduledStart)?.atZone(zone)
        if (oldStart?.toLocalDate() != newStart?.toLocalDate()) fields.add("日期")
        val oldKind = old.scheduleKind ?: "exactTime"
        val newKind = new.scheduleKind ?: "exactTime"
        val oldTime = oldStart?.let { it.hour to it.minute }
        val newTime = newStart?.let { it.hour to it.minute }
        if (oldKind != newKind || (newKind == "exactTime" && oldTime != newTime)) fields.add("时间")
        if (AIDrafting.plannedDurationMinutes(old) != AIDrafting.plannedDurationMinutes(new)) fields.add("时长")
        if (old.category != new.category) fields.add("类型")
        if (old.repeatRule != new.repeatRule) fields.add("重复")
        if (old.title != new.title) fields.add("标题")
        return fields
    }

    /** 改了「交周报」的日期、时间 */
    fun entryChange(title: String, fields: List<String>): String =
        "改了「${AIDailyBrief.shortTitle(title)}」的${fields.joinToString("、")}"

    fun imported(count: Int, origin: String): String = when (origin) {
        imageOrigin -> "从截图加入 $count 条"
        shareOrigin -> "从分享加入 $count 条"
        else -> "从文字加入 $count 条"
    }

    fun nextWeek(count: Int): String = "拟下周 $count 条"

    fun preparation(count: Int, title: String): String = "为「${AIDailyBrief.shortTitle(title)}」拟 $count 条"

    /** 收尾：顺延 1 件，明早 2 件，放下 1 件 */
    fun wrap(tomorrow: Int, morning: Int, dropped: Int): String {
        val parts = mutableListOf<String>()
        if (tomorrow > 0) parts.add("顺延 $tomorrow 件")
        if (morning > 0) parts.add("明早 $morning 件")
        if (dropped > 0) parts.add("放下 $dropped 件")
        return "收尾：" + parts.joinToString("，")
    }

    /** The same three sentences as the undo bar. */
    fun undoResult(undone: Int, total: Int): String = when {
        undone == total -> "已撤销"
        undone > 0 -> "已撤销 $undone 条；之后改过的保持不变"
        else -> "这些计划之后都改过，保持不变"
    }
}

/**
 * 今天的整理: every write Moji made, kept for seven days in the AI
 * preferences file (not the snapshot, not backups, not the widget).
 */
class AIWriteLog(private val prefs: AIPreferences, now: Instant = Instant.now(), private val zone: ZoneId = ZoneId.systemDefault()) {
    private val state = MutableStateFlow(pruned(decode(prefs.getString(key)), now, zone))

    val entries: StateFlow<List<AIWriteEntry>> = state.asStateFlow()

    fun record(summary: String, added: List<CheckInItem>, before: List<CheckInItem>, after: List<CheckInItem>,
               at: Instant = Instant.now()): String {
        val entry = AIWriteEntry(UUID.randomUUID().toString(), at.toEpochMilli() / 1000.0, summary, added, before, after, false)
        state.value = pruned(state.value + entry, at, zone)
        save()
        return entry.id
    }

    fun markUndone(id: String) {
        val index = state.value.indexOfFirst { it.id == id }
        if (index < 0 || state.value[index].undone) return
        state.value = state.value.mapIndexed { i, entry -> if (i == index) entry.copy(undone = true) else entry }
        save()
    }

    fun entry(id: String): AIWriteEntry? = state.value.firstOrNull { it.id == id }

    /** That day's writes, newest first. */
    fun entries(day: LocalDate): List<AIWriteEntry> = entriesOn(state.value, day, zone)

    /** 今天墨记替你整理了 N 处: the writes still standing. */
    fun activeCount(day: LocalDate): Int = activeCount(state.value, day, zone)

    /** Three writes for the photographed screens, one of them taken back. */
    fun seedForQA(plans: List<CheckInItem>, now: ZonedDateTime) {
        fun at(hour: Int, minute: Int) = now.toLocalDate().atTime(hour, minute).atZone(now.zone).toInstant().toEpochMilli() / 1000.0
        val first = plans.take(1)
        state.value = listOf(
            AIWriteEntry(UUID.randomUUID().toString(), at(9, 12), AIWriteText.breakdown(3, "准备季度汇报", true),
                emptyList(), emptyList(), emptyList(), false),
            AIWriteEntry(UUID.randomUUID().toString(), at(13, 40), AIWriteText.entryChange(first.firstOrNull()?.title ?: "交周报", listOf("日期", "时间")),
                emptyList(), first, first, true),
            AIWriteEntry(UUID.randomUUID().toString(), at(18, 5), AIWriteText.imported(2, AIWriteText.textOrigin),
                emptyList(), emptyList(), emptyList(), false),
        )
        save()
    }

    private fun save() {
        prefs.putString(key, encode(state.value))
    }

    companion object {
        const val key = "moji.ai.writes.v1"
        const val keepDays = 7
        const val limit = 200

        private val codec = Json(MojiJson.codec) { prettyPrint = false }
        private val plansSerializer = ListSerializer(CheckInItem.serializer())

        /** Entries from the last seven days, at most 200, oldest dropped first. */
        fun pruned(entries: List<AIWriteEntry>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<AIWriteEntry> {
            val start = now.atZone(zone).toLocalDate().minusDays((keepDays - 1).toLong()).atStartOfDay(zone).toInstant()
            val kept = entries.filter { !it.instant.isBefore(start) }
            return if (kept.size > limit) kept.takeLast(limit) else kept
        }

        fun entriesOn(entries: List<AIWriteEntry>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AIWriteEntry> =
            entries.filter { it.day(zone) == day }.sortedByDescending { it.at }

        fun activeCount(entries: List<AIWriteEntry>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int =
            entriesOn(entries, day, zone).count { !it.undone }

        fun encode(entries: List<AIWriteEntry>): String = buildJsonArray {
            for (entry in entries) add(buildJsonObject {
                put("id", entry.id)
                put("at", entry.at)
                put("summary", entry.summary)
                put("added", codec.encodeToJsonElement(plansSerializer, entry.added))
                put("before", codec.encodeToJsonElement(plansSerializer, entry.before))
                put("after", codec.encodeToJsonElement(plansSerializer, entry.after))
                put("undone", entry.undone)
            })
        }.toString()

        fun decode(text: String?): List<AIWriteEntry> {
            val array = text?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
                ?: return emptyList()
            return array.mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val id = item["id"].aiString() ?: return@mapNotNull null
                val at = (item["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@mapNotNull null
                fun plans(name: String): List<CheckInItem> =
                    item[name]?.let { runCatching { codec.decodeFromJsonElement(plansSerializer, it) }.getOrNull() }.orEmpty()
                AIWriteEntry(id, at, item["summary"].aiString() ?: "", plans("added"), plans("before"), plans("after"),
                    item["undone"].aiBool() ?: false)
            }
        }

        @Volatile private var installed: AIWriteLog? = null
        @Volatile private var fixture: AIWriteLog? = null
        @Volatile private var fallback: AIWriteLog? = null

        val shared: AIWriteLog
            get() {
                if (AIQAFixture.isActive) {
                    return fixture ?: synchronized(this) { fixture ?: AIWriteLog(AIMemoryPreferences()).also { fixture = it } }
                }
                installed?.let { return it }
                return synchronized(this) { fallback ?: AIWriteLog(AIMemoryPreferences()).also { fallback = it } }
            }

        /** Called with the AI preferences file from `AISettingsStore.install`. */
        fun install(prefs: AIPreferences) {
            if (installed != null) return
            synchronized(this) { if (installed == null) installed = AIWriteLog(prefs) }
        }

        /** The QA fixture starts each launch with nothing remembered. */
        internal fun resetFixture() {
            synchronized(this) { fixture = null }
        }
    }
}
