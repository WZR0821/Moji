package com.raydon.moji.android.ai

import com.raydon.moji.android.data.PlanQuickChange
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.PlanWorkflow
import com.raydon.moji.core.PlanWorkflowUndo
import java.text.BreakIterator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Ported from Moji/AI/AIDrafting.swift. Days are java.time LocalDate in the
// device zone; plan types are the raw strings CheckInItem.category stores.

/** A plan the model proposed. Nothing is saved until the user accepts it. */
data class PlanDraft(
    var id: String = UUID.randomUUID().toString(),
    var title: String,
    /** The day the plan would land on. */
    var day: LocalDate,
    var scheduleKind: String = "allDay",
    var hour: Int = 0,
    var minute: Int = 0,
    var minutes: Int? = null,
    var category: String,
    /** Why the model proposed it; shown under the row, never saved. */
    var reason: String = "",
    var isSelected: Boolean = true,
) {
    /** A new plan, in the same shape the editor writes. */
    fun plan(note: String = "", zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): CheckInItem {
        val startHour = AIDrafting.representativeHour(scheduleKind) ?: hour.coerceIn(0, 23)
        val startMinute = if (scheduleKind == "exactTime") minute.coerceIn(0, 59) else 0
        return CheckInItem(
            id = UUID.randomUUID().toString(),
            title = title,
            category = category,
            scheduledStart = day.atTime(startHour, startMinute).atZone(zone).toInstant().toString(),
            plannedMinutes = minutes ?: 25,
            note = note,
            createdAt = now.toString(),
            scheduleKind = scheduleKind,
            plannedDurationEnabled = minutes != null,
        )
    }

    val scheduleText: String
        get() = if (scheduleKind == "exactTime") "%02d:%02d".format(Locale.ROOT, hour, minute) else AIDrafting.scheduleName(scheduleKind)

    fun metaText(now: LocalDate = LocalDate.now()): String {
        val parts = mutableListOf(AIDrafting.dayText(day, now))
        if (scheduleKind != "allDay") parts.add(scheduleText)
        minutes?.let { parts.add(AIDrafting.durationText(it)) }
        parts.add(AIDrafting.categoryDisplayName(category))
        return parts.joinToString(" · ")
    }
}

/**
 * What was read out of one typed plan. Every field is optional: a field the
 * sentence did not mention stays null and is left alone.
 */
data class EntrySuggestion(
    val title: String? = null,
    val day: LocalDate? = null,
    val scheduleKind: String? = null,
    val hour: Int = 0,
    val minute: Int = 0,
    val minutes: Int? = null,
    val category: String? = null,
    val repeatRule: String? = null,
    /** Two or more when the sentence holds several separate things. */
    val parts: List<String> = emptyList(),
) {
    val hasDetails: Boolean
        get() = day != null || scheduleKind != null || minutes != null || category != null || repeatRule != null

    val isEmpty: Boolean get() = !hasDetails && parts.size < 2

    /** The words on the suggestion strip, in reading order. */
    fun chips(now: LocalDate = LocalDate.now()): List<String> {
        val list = mutableListOf<String>()
        day?.let { list.add(AIDrafting.dayText(it, now)) }
        scheduleKind?.let { list.add(if (it == "exactTime") "%02d:%02d".format(Locale.ROOT, hour, minute) else AIDrafting.scheduleName(it)) }
        minutes?.let { list.add(AIDrafting.durationText(it)) }
        category?.let { list.add(AIDrafting.categoryDisplayName(it)) }
        repeatRule?.let { list.add(AIDrafting.repeatName(it)) }
        return list
    }

    /** The plan with every recognised field applied; null when nothing changes. */
    fun applied(original: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): CheckInItem? {
        var item = original
        val changes = mutableListOf<PlanQuickChange>()
        if (!title.isNullOrEmpty() && title != original.title) changes.add(PlanQuickChange.Content(title, original.note))
        day?.let { changes.add(PlanQuickChange.Date(it)) }
        if (scheduleKind != null || minutes != null) {
            // A length on its own keeps the time the plan already has.
            val time = AIDrafting.instant(original.scheduledStart)?.atZone(zone)?.toLocalTime() ?: LocalTime.MIDNIGHT
            changes.add(PlanQuickChange.Schedule(
                scheduleKind ?: (original.scheduleKind ?: "exactTime"),
                if (scheduleKind == null) LocalTime.of(time.hour, time.minute) else LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)),
                minutes ?: AIDrafting.plannedDurationMinutes(original),
            ))
        }
        category?.let { changes.add(PlanQuickChange.Category(it)) }
        repeatRule?.let { changes.add(PlanQuickChange.Recurrence(it, emptySet())) }
        for (change in changes) item = change.applyTo(item, zone) ?: continue
        return if (item == original) null else item
    }

    /**
     * The suggestion without what the plan already says, so the strip only
     * offers changes. The chips adopted before adding are already on the
     * plan, and the model reads the same words again.
     */
    fun removingMatches(plan: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): EntrySuggestion {
        var value = this
        val start = AIDrafting.instant(plan.scheduledStart)?.atZone(zone)
        if (day != null && start != null && start.toLocalDate() == day) value = value.copy(day = null)
        if (scheduleKind != null && scheduleKind == (plan.scheduleKind ?: "exactTime")) {
            if (scheduleKind != "exactTime" || (start != null && start.hour == hour && start.minute == minute)) value = value.copy(scheduleKind = null)
        }
        if (minutes != null && minutes == AIDrafting.plannedDurationMinutes(plan)) value = value.copy(minutes = null)
        if (category != null && category == plan.category) value = value.copy(category = null)
        if (repeatRule != null && repeatRule == (plan.repeatRule ?: "never")) value = value.copy(repeatRule = null)
        if (title != null && title == plan.title) value = value.copy(title = null)
        return value
    }
}

data class ReflectionDraft(
    val note: String,
    val nextFocus: String,
    val evidence: List<Evidence> = emptyList(),
) {
    /** Two pieces of evidence are equal when they say the same; the id is only for lists. */
    class Evidence(
        val point: String,
        /** Titles of the plans the point rests on, looked up locally. */
        val planTitles: List<String>,
        val id: String = UUID.randomUUID().toString(),
    ) {
        override fun equals(other: Any?): Boolean =
            other is Evidence && other.point == point && other.planTitles == planTitles

        override fun hashCode(): Int = 31 * point.hashCode() + planTitles.hashCode()

        override fun toString(): String = "Evidence(point=$point, planTitles=$planTitles)"
    }
}

data class ConversionHint(val day: LocalDate? = null, val category: String? = null) {
    fun text(now: LocalDate = LocalDate.now()): String =
        listOfNotNull(day?.let { AIDrafting.dayText(it, now) }, category?.let { AIDrafting.categoryDisplayName(it) })
            .joinToString(" · ")
}

/** The pickers and the search word of the plan library, in PlanLibraryDialog's values. */
data class LibraryFilter(
    val keyword: String = "",
    val category: String = "all",
    val status: String = "all",
    val period: String = "all",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/** Plans a draft added or changed: one step of undo. */
data class PlanDraftUndo(val added: List<CheckInItem>, val changed: PlanWorkflowUndo) {
    /**
     * Takes back only what has not been touched since. A plan that was edited,
     * started or finished after the draft was accepted stays as it is.
     * Returns the new state and how many plans were taken back.
     */
    fun apply(state: PlanSnapshot, blockedId: String? = null): Pair<PlanSnapshot, Int> {
        // core's PlanWorkflowUndo.apply has no count, so count the plans it restores.
        val restored = state.checkInItems.count { current ->
            changed.before.any { it.id == current.id } && changed.after.firstOrNull { it.id == current.id } == current &&
                state.activeSession?.checkInItemID != current.id && current.id != blockedId
        }
        var result = changed.apply(state, blockedId)
        var count = restored
        for (plan in added) {
            val index = result.checkInItems.indexOfFirst { it.id == plan.id }
            if (index < 0 || result.checkInItems[index] != plan ||
                result.activeSession?.checkInItemID == plan.id || plan.id == blockedId) continue
            result = result.copy(checkInItems = result.checkInItems.filterIndexed { i, _ -> i != index })
            count += 1
        }
        return result to count
    }
}

/** One week as plain facts, with the plans numbered so a point can cite them. */
data class WeekRecord(val text: String, val plans: List<CheckInItem>)

/**
 * Instructions sent to the model and the reading of its answers. Everything
 * here is pure, so the same Chinese samples can be replayed in tests.
 */
object AIDrafting {
    const val maximumInputCharacters = 6_000

    // Shared wording

    const val preface = "你是“墨记”（一个计划与备忘 App）里的整理助手。只输出一个 JSON 对象，不要解释，不要用代码块包裹。" +
        "文字字段使用用户原文的语言。不要编造用户没有写的事情。"

    private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun todayLine(now: ZonedDateTime): String {
        val day = now.toLocalDate()
        return "今天是 %04d-%02d-%02d（%s）。".format(Locale.ROOT, day.year, day.monthValue, day.dayOfMonth, weekdayNames[day.dayOfWeek.value - 1])
    }

    val dateFields = listOf(
        "日期用下面三组字段之一表示，其余填 null；原文没有时间线索时三组都填 null：",
        """"date"："YYYY-MM-DD"，原文写了明确日期（如 12月5日）时用；""",
        """"offset_days"：整数，今天=0、明天=1、后天=2、三天后=3；""",
        """"weekday"：1–7（周一=1…周日=7），配合 "week_offset"：0=本周、1=下周、2=下下周（没说“下周”就填 0）。""",
    ).joinToString("\n")

    fun categoryLine(categories: List<String>): String =
        "类型只能从这些里选一个，拿不准就填 null：" + categories.joinToString("、") { categoryDisplayName(it) } + "。"

    // Instructions

    fun entryInstructions(now: ZonedDateTime, categories: List<String>): String = listOf(
        preface,
        "任务：把用户写下的一句计划拆出结构。" + todayLine(now),
        "输出 JSON：",
        """{"title": 去掉日期、钟点、时长之后的事情本身，尽量保留原话,""",
        """ "date": …, "offset_days": …, "weekday": …, "week_offset": …,""",
        """ "part": "morning"|"afternoon"|"evening"|"exact"|null（exact 表示原话写了具体钟点）,""",
        """ "time": "HH:mm"|null（24 小时制，只在 part 为 exact 时填）,""",
        """ "minutes": 整数|null（原话写了要花多久才填，单位分钟）,""",
        """ "category": 类型名|null,""",
        """ "repeat": "daily"|"weekdays"|"weekly"|"monthly"|null,""",
        """ "items": 字符串数组（只有原话明显是几件互不相同的事时才逐件列出，每件保留原话；否则给空数组）}""",
        dateFields,
        categoryLine(categories),
        "原话没有提到的字段一律填 null，不要猜。",
    ).joinToString("\n")

    val breakdownInstructions = listOf(
        preface,
        "任务：把一件计划拆成可以直接动手的小步骤。3 到 6 步，每步一句话、以动词开头、不超过 20 个字；" +
            "第一步要小到十分钟内能开始。不要加入原计划没有涉及的目标。",
        """输出 JSON：{"steps":[{"title":"…","minutes":整数|null}]}""",
    ).joinToString("\n")

    val checklistInstructions = listOf(
        preface,
        "任务：把一则备忘里要做的事整理成清单。每项一句话，保留原文里的名词和数字；" +
            "感想、背景这类不是待办的内容不要列入；不要添加原文没有的事。最多 20 项。",
        """输出 JSON：{"items":["…"]}""",
    ).joinToString("\n")

    fun conversionInstructions(now: ZonedDateTime, categories: List<String>): String = listOf(
        preface,
        "任务：下面是一则备忘里准备转成计划的几行，每行前有编号。为每一行判断计划日期和类型。" + todayLine(now),
        """输出 JSON：{"rows":[{"index":编号,"date":…,"offset_days":…,"weekday":…,"week_offset":…,"category":类型名|null}]}""",
        dateFields,
        categoryLine(categories),
    ).joinToString("\n")

    val reflectionInstructions = listOf(
        preface,
        "任务：根据下面这份一周记录，替用户起草周复盘。只依据记录里的事实；数字只能照抄记录，不要推算新的数字；" +
            "不评价用户的为人。语气平实，像用户写给自己的话，不用“你”。",
        "输出 JSON：",
        """{"note": 本周复盘，2 到 4 句，写做得好的事和遇到的问题,""",
        """ "next_focus": 给下周的一句提醒，不超过 40 字,""",
        """ "evidence":[{"point":"一句话结论","plans":[所依据的计划编号]}]}""",
        "evidence 给 1 到 3 条，编号只能用记录里出现过的。",
    ).joinToString("\n")

    fun nextWeekInstructions(categories: List<String>): String = listOf(
        preface,
        "任务：根据下面的记录，替用户拟下周的计划草稿，3 到 6 条。优先安排没做完的事（太大的可以拆小）" +
            "和“下周关注”里提到的方向；记录里完全没有线索的新目标不要安排。",
        """输出 JSON：{"plans":[{"title":"…","weekday":1–7（周一=1）,"part":"morning"|"afternoon"|"evening"|null,""" +
            """"minutes":整数|null,"category":类型名|null,"reason":"为什么安排它，不超过 20 字"}]}""",
        categoryLine(categories),
    ).joinToString("\n")

    fun preparationInstructions(daysLeft: Int, categories: List<String>): String = listOf(
        preface,
        "任务：用户有一个重要的日子。替它拟一份准备计划，3 到 6 条，由远到近，每条是一件具体可做的事。" +
            "只围绕这个日子本身，不要臆测用户没有提到的细节（比如具体科目、地点、人名）。",
        """输出 JSON：{"plans":[{"title":"…","days_before":整数（提前几天做，0 表示当天，不能超过 """ + daysLeft + """）,""" +
            """"minutes":整数|null,"category":类型名|null,"reason":"不超过 20 字"}]}""",
        categoryLine(categories),
    ).joinToString("\n")

    fun filterInstructions(now: ZonedDateTime, categories: List<String>): String = listOf(
        preface,
        "任务：把用户的一句话变成计划库的筛选条件。" + todayLine(now),
        "输出 JSON：",
        """{"keyword": 要在标题和说明里搜索的词|null（只在原话点名了某件事或某个词时填）,""",
        """ "category": 类型名|null,""",
        """ "status": "all"|"planned"|"inProgress"|"completed"|"skipped"|"archived",""",
        """ "period": "all"|"past"|"today"|"future"|"this_week"|"last_week"|"next_week"|"this_month"|"last_month"|"range",""",
        """ "from": "YYYY-MM-DD"|null, "to": "YYYY-MM-DD"|null}""",
        "“没做完”“待办”是 planned，“做完的”是 completed。只有原话给了明确起止日期时 period 才用 range 并填 from 和 to。",
        categoryLine(categories),
    ).joinToString("\n")

    fun importInstructions(now: ZonedDateTime, categories: List<String>): String = listOf(
        preface,
        "任务：从用户粘贴的一段文字里找出要做的事，整理成计划草稿。每件事一条，标题保留原文的名词和数字、不超过 30 字；" +
            "不是待办的内容不要列入；不要添加原文没有的事。最多 12 条。" + todayLine(now),
        """输出 JSON：{"plans":[{"title":"…","date":…,"offset_days":…,"weekday":…,"week_offset":…,""" +
            """"part":"morning"|"afternoon"|"evening"|"exact"|null,"time":"HH:mm"|null,"minutes":整数|null,"category":类型名|null}]}""",
        dateFields,
        categoryLine(categories),
        "原文没有提到的字段填 null。",
    ).joinToString("\n")

    // Local judgement

    /** A short plain title has nothing to read; asking would only send it out. */
    fun isWorthReading(text: String): Boolean {
        val clean = text.trim()
        if (characterCount(clean) !in 3..200) return false
        if (clean.any { it.isDigit() }) return true
        val cues = listOf("今天", "明天", "后天", "今晚", "明早", "明晚", "周", "星期", "礼拜", "月", "号", "早上", "上午",
            "中午", "下午", "晚上", "点", "半", "分钟", "小时", "每天", "每周", "每月", "每个", "工作日",
            "、", "，", ",", "；", ";", "然后", "还有", "以及", "和")
        return cues.any { clean.contains(it) }
    }

    /** A search word is a word; a sentence is something to turn into filters. */
    fun looksLikeFilterSentence(text: String): Boolean {
        val clean = text.trim()
        if (characterCount(clean) !in 4..80) return false
        val cues = listOf("没做", "做完", "完成", "待办", "跳过", "归档", "上周", "这周", "本周", "下周", "上个月", "这个月",
            "本月", "今天", "过去", "以前", "之前", "未来", "以后", "之后", "的计划", "所有", "全部", "哪些")
        return cues.any { clean.contains(it) }
    }

    fun dayText(day: LocalDate, now: LocalDate = LocalDate.now()): String = when (ChronoUnit.DAYS.between(now, day)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> "${day.monthValue}月${day.dayOfMonth}日 ${weekdayNames[day.dayOfWeek.value - 1]}"
    }

    // Reading answers

    /** The JSON object in a model's answer, tolerating a code fence or a sentence around it. */
    fun `object`(answer: String): JsonObject {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end < 0 || start >= end) throw AIRequestError.unreadableAnswer
        val parsed = runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)) }.getOrNull()
        return parsed as? JsonObject ?: throw AIRequestError.unreadableAnswer
    }

    fun string(value: JsonElement?): String? {
        val text = value.aiString() ?: return null
        val clean = text.trim()
        return if (clean.isEmpty() || clean.lowercase() == "null") null else clean
    }

    fun integer(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content.trim { it == ' ' || it == '\t' }.toIntOrNull()
        // JSON true and false are not numbers here: they would read as 0 and 1
        // and lose “明天” and row 0.
        if (primitive.booleanOrNull != null) return null
        val amount = primitive.doubleOrNull ?: return null
        if (!amount.isFinite() || abs(amount) >= 1_000_000) return null
        // Swift's rounded(): halves go away from zero.
        return (if (amount >= 0) floor(amount + 0.5) else -floor(-amount + 0.5)).toInt()
    }

    fun category(value: JsonElement?, categories: List<String>): String? {
        val name = string(value) ?: return null
        return categories.firstOrNull { categoryDisplayName(it).equals(name, ignoreCase = true) }
    }

    /** Minutes are kept only when they are a believable length for one plan. */
    fun minutes(value: JsonElement?): Int? = integer(value)?.takeIf { it in 1..1_440 }

    /** A day from the three date forms the instructions allow. The arithmetic is done here, not by the model. */
    fun day(values: JsonObject, now: ZonedDateTime): LocalDate? {
        val today = now.toLocalDate()
        string(values["date"])?.let { text ->
            val parts = text.split("-").mapNotNull { it.toIntOrNull() }
            if (parts.size == 3 && text.length == 10) {
                runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()?.let { return it }
            }
        }
        integer(values["offset_days"])?.let { offset -> if (offset in 0..730) return today.plusDays(offset.toLong()) }
        val weekday = integer(values["weekday"])
        if (weekday != null && weekday in 1..7) {
            val weeks = (integer(values["week_offset"]) ?: 0).coerceIn(0, 8)
            val monday = weekStart(today)
            var target = monday.plusDays((weeks * 7 + weekday - 1).toLong())
            // “周三” said on a Thursday means the coming Wednesday.
            if (weeks == 0 && target < today) target = target.plusDays(7)
            return target
        }
        return null
    }

    private data class Schedule(val kind: String, val hour: Int, val minute: Int)

    private fun schedule(values: JsonObject): Schedule? = when (string(values["part"])) {
        "morning" -> Schedule("morning", 0, 0)
        "afternoon" -> Schedule("afternoon", 0, 0)
        "evening" -> Schedule("evening", 0, 0)
        "exact" -> {
            val parts = string(values["time"])?.split(":")?.mapNotNull { it.toIntOrNull() }
            if (parts != null && parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59) Schedule("exactTime", parts[0], parts[1]) else null
        }
        else -> null
    }

    private fun title(value: JsonElement?, limit: Int = 80): String? {
        val text = string(value) ?: return null
        return prefix(text.replace(newlines, " "), limit)
    }

    fun entrySuggestion(answer: String, original: String, now: ZonedDateTime, categories: List<String>): EntrySuggestion {
        val values = `object`(answer)
        val clean = original.trim()
        var day = day(values, now)
        val schedule = schedule(values)
        val repeat = when (string(values["repeat"])) {
            "daily" -> "daily"; "weekdays" -> "weekdays"; "weekly" -> "weekly"; "monthly" -> "monthly"; else -> null
        }
        val items = (values["items"] as? JsonArray).orEmpty().mapNotNull { title(it) }.distinct()
        // Today is where a quick plan already lands.
        if (day == now.toLocalDate()) day = null
        var result = EntrySuggestion(
            title = title(values["title"])?.takeIf { it != clean },
            day = day,
            scheduleKind = schedule?.kind,
            hour = schedule?.hour ?: 0,
            minute = schedule?.minute ?: 0,
            minutes = minutes(values["minutes"]),
            category = category(values["category"], categories),
            repeatRule = repeat,
            parts = if (items.size in 2..8) items else emptyList(),
        )
        // A title on its own is not worth a strip under the row.
        if (!result.hasDetails) result = result.copy(title = null)
        return result
    }

    fun steps(answer: String, plan: CheckInItem, now: ZonedDateTime = ZonedDateTime.now()): List<PlanDraft> {
        val values = `object`(answer)
        // Steps of a carried-over plan start today, not on the day it was first meant for.
        val planned = instant(plan.scheduledStart)?.atZone(now.zone)?.toLocalDate() ?: now.toLocalDate()
        val day = maxOf(planned, now.toLocalDate())
        val drafts = (values["steps"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val item = raw as? JsonObject
            val text = title(if (item != null) item["title"] ?: raw else raw, limit = 60) ?: return@mapNotNull null
            PlanDraft(title = text, day = day, minutes = minutes(item?.get("minutes")), category = plan.category)
        }
        if (drafts.isEmpty()) throw AIRequestError.unreadableAnswer
        return drafts.take(8)
    }

    fun checklist(answer: String): List<String> {
        val values = `object`(answer)
        val items = (values["items"] as? JsonArray).orEmpty().mapNotNull { title(it, limit = 120) }.distinct()
        if (items.isEmpty()) throw AIRequestError.unreadableAnswer
        return items.take(30)
    }

    fun conversionHints(answer: String, rowCount: Int, now: ZonedDateTime, categories: List<String>): Map<Int, ConversionHint> {
        val values = `object`(answer)
        val hints = linkedMapOf<Int, ConversionHint>()
        for (raw in (values["rows"] as? JsonArray).orEmpty()) {
            val row = raw as? JsonObject ?: continue
            val index = integer(row["index"]) ?: continue
            if (index !in 0 until rowCount) continue
            val hint = ConversionHint(day(row, now), category(row["category"], categories))
            if (hint.day != null || hint.category != null) hints[index] = hint
        }
        return hints
    }

    fun reflection(answer: String, plans: List<CheckInItem>): ReflectionDraft {
        val values = `object`(answer)
        val note = string(values["note"]) ?: throw AIRequestError.unreadableAnswer
        val evidence = (values["evidence"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val point = string(item["point"]) ?: return@mapNotNull null
            val titles = (item["plans"] as? JsonArray).orEmpty().mapNotNull { integer(it) }
                .filter { it >= 1 && it <= plans.size }.map { plans[it - 1].title }
            // A point that rests on nothing in the record is not shown.
            if (titles.isEmpty()) return@mapNotNull null
            ReflectionDraft.Evidence(prefix(point, 80), titles.take(4))
        }
        return ReflectionDraft(prefix(note, 600), prefix(string(values["next_focus"]) ?: "", 120), evidence.take(3))
    }

    private fun planDrafts(
        values: JsonObject,
        limit: Int,
        fallbackCategory: String,
        categories: List<String>,
        day: (JsonObject) -> LocalDate?,
    ): List<PlanDraft> {
        val drafts = (values["plans"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val text = title(item["title"], limit = 60) ?: return@mapNotNull null
            val date = day(item) ?: return@mapNotNull null
            val schedule = schedule(item)
            PlanDraft(
                title = text, day = date,
                scheduleKind = schedule?.kind ?: "allDay", hour = schedule?.hour ?: 0, minute = schedule?.minute ?: 0,
                minutes = minutes(item["minutes"]),
                category = category(item["category"], categories) ?: fallbackCategory,
                reason = prefix(string(item["reason"]) ?: "", 40),
            )
        }
        if (drafts.isEmpty()) throw AIRequestError.unreadableAnswer
        return drafts.take(limit)
    }

    fun nextWeekDrafts(answer: String, week: LocalDate, fallbackCategory: String, categories: List<String>): List<PlanDraft> {
        val monday = weekStart(week)
        return planDrafts(`object`(answer), 8, fallbackCategory, categories) { item ->
            val weekday = integer(item["weekday"])
            if (weekday != null && weekday in 1..7) monday.plusDays((7 + weekday - 1).toLong()) else null
        }
    }

    fun preparationDrafts(
        answer: String,
        target: LocalDate,
        now: ZonedDateTime,
        fallbackCategory: String,
        categories: List<String>,
    ): List<PlanDraft> {
        val today = now.toLocalDate()
        val drafts = planDrafts(`object`(answer), 8, fallbackCategory, categories) { item ->
            val before = integer(item["days_before"])
            // A step can never land in the past or after the day itself.
            if (before != null && before >= 0) minOf(target, maxOf(today, target.minusDays(before.toLong()))) else null
        }
        return drafts.sortedBy { it.day }
    }

    fun importDrafts(answer: String, now: ZonedDateTime, fallbackCategory: String, categories: List<String>): List<PlanDraft> {
        val today = now.toLocalDate()
        return planDrafts(`object`(answer), 12, fallbackCategory, categories) { item ->
            maxOf(today, day(item, now) ?: today)
        }
    }

    fun libraryFilter(answer: String, now: ZonedDateTime, categories: List<String>): LibraryFilter {
        val values = `object`(answer)
        var filter = LibraryFilter(keyword = prefix(string(values["keyword"]) ?: "", 30))
        category(values["category"], categories)?.let { filter = filter.copy(category = it) }
        string(values["status"])?.let { status ->
            if (status in listOf("planned", "inProgress", "completed", "skipped", "archived")) filter = filter.copy(status = status)
        }
        val today = now.toLocalDate()
        val monday = weekStart(today)
        fun week(offset: Long) {
            val start = monday.plusDays(offset * 7)
            filter = filter.copy(period = "range", from = start, to = start.plusDays(6))
        }
        fun month(offset: Long) {
            val start = today.withDayOfMonth(1).plusMonths(offset)
            filter = filter.copy(period = "range", from = start, to = start.plusMonths(1).minusDays(1))
        }
        when (string(values["period"])) {
            "past" -> filter = filter.copy(period = "past")
            "today" -> filter = filter.copy(period = "today")
            "future" -> filter = filter.copy(period = "future")
            "this_week" -> week(0)
            "last_week" -> week(-1)
            "next_week" -> week(1)
            "this_month" -> month(0)
            "last_month" -> month(-1)
            "range" -> {
                val from = day(JsonObject(mapOf("date" to (values["from"] ?: JsonNull))), now)
                val to = day(JsonObject(mapOf("date" to (values["to"] ?: JsonNull))), now)
                if (from != null && to != null && from <= to) filter = filter.copy(period = "range", from = from, to = to)
            }
        }
        if (filter == LibraryFilter()) throw AIRequestError.unreadableAnswer
        return filter
    }

    // What is sent

    fun breakdownInput(plan: CheckInItem): String {
        val lines = mutableListOf("计划：${plan.title}")
        if (plan.note.isNotEmpty()) lines.add("备注：${plan.note}")
        plannedDurationMinutes(plan)?.let { lines.add("预计投入：$it 分钟") }
        return lines.joinToString("\n")
    }

    fun conversionInput(memoTitle: String, rows: List<String>): String {
        val lines = mutableListOf<String>()
        if (memoTitle.isNotEmpty()) lines.add("备忘标题：$memoTitle")
        rows.forEachIndexed { index, row -> lines.add("[$index] ${prefix(row, 200)}") }
        return lines.joinToString("\n")
    }

    /**
     * One week as plain facts, with the plans numbered so that a point in the
     * draft can cite them. Every number in it is computed here.
     */
    fun weekRecord(snapshot: PlanSnapshot, week: LocalDate, now: ZonedDateTime): WeekRecord {
        val zone = now.zone
        val start = weekStart(week)
        val end = start.plusDays(6)
        val summary = summary(snapshot, start, zone, null)
        val skipped = summary.scheduled.filter { it.status == "skipped" }
        val open = (summary.carryover + summary.pending).sortedBy { instant(it.scheduledStart) ?: Instant.EPOCH }
        val plans = mutableListOf<CheckInItem>()
        fun number(plan: CheckInItem): Int { plans.add(plan); return plans.size }
        fun actual(plan: CheckInItem): Int {
            val seconds = snapshot.records.filter { it.checkInItemID == plan.id && (it.scheduleKind ?: "exactTime") == "exactTime" }
                .sumOf { record ->
                    val from = instant(record.startDate); val to = instant(record.endDate)
                    if (from == null || to == null) 0.0 else maxOf(0.0, (to.toEpochMilli() - from.toEpochMilli()) / 1000.0)
                }
            return if (seconds > 0) ceil(seconds / 60).toInt() else 0
        }
        fun dayLabel(date: LocalDate) = "${date.monthValue}月${date.dayOfMonth}日"
        val lines = mutableListOf(
            "这一周：${dayLabel(start)}–${dayLabel(end)}",
            "完成 ${summary.completed.size} 项，没做完 ${open.size} 项，跳过 ${skipped.size} 项；" +
                "预计投入 ${summary.estimatedMinutes} 分钟，实际专注 ${summary.actualMinutes} 分钟。",
        )
        val key = start.toString()
        for (goal in snapshot.weeklyGoals.filter { it.weekStart == key }) {
            val progress = summary(snapshot, start, zone, goal.category)
            val parts = mutableListOf<String>()
            goal.targetCount?.let { parts.add("完成 $it 项（已 ${progress.completed.size} 项）") }
            goal.targetMinutes?.let { parts.add("专注 $it 分钟（已 ${progress.actualMinutes} 分钟）") }
            lines.add("周目标（${goal.category?.let { categoryDisplayName(it) } ?: "全部类型"}）：" + parts.joinToString("，"))
        }
        if (summary.completed.isNotEmpty()) {
            lines.add("已完成：")
            for (plan in summary.completed.take(30)) {
                val minutes = actual(plan)
                lines.add("[${number(plan)}] ${plan.title}（${categoryDisplayName(plan.category)}${if (minutes > 0) "，实际 $minutes 分钟" else ""}）")
            }
        }
        if (open.isNotEmpty()) {
            lines.add("没做完：")
            for (plan in open.take(30)) {
                val late = daysLate(plan.scheduledStart, now)
                lines.add("[${number(plan)}] ${plan.title}（${categoryDisplayName(plan.category)}${late?.let { "，已顺延 $it 天" } ?: ""}）")
            }
        }
        if (skipped.isNotEmpty()) {
            lines.add("已跳过：")
            for (plan in skipped.take(15)) lines.add("[${number(plan)}] ${plan.title}（${categoryDisplayName(plan.category)}）")
        }
        return WeekRecord(lines.joinToString("\n"), plans)
    }

    fun nextWeekInput(snapshot: PlanSnapshot, week: LocalDate, now: ZonedDateTime): String {
        var text = weekRecord(snapshot, week, now).text
        val key = weekStart(week).toString()
        snapshot.weeklyReflections.firstOrNull { it.weekStart == key }?.let { reflection ->
            if (reflection.note.isNotEmpty()) text += "\n本周复盘：${prefix(reflection.note, 600)}"
            if (reflection.nextFocus.isNotEmpty()) text += "\n下周关注：${prefix(reflection.nextFocus, 200)}"
        }
        return text
    }

    fun preparationInput(title: String, target: LocalDate, daysLeft: Int): String =
        "日子：%s\n日期：%04d-%02d-%02d（还有 %d 天）".format(Locale.ROOT, title, target.year, target.monthValue, target.dayOfMonth, daysLeft)

    // Shared helpers, matching the Android app's own rules

    /** Monday of the week, as PlanWorkflow.weekStart and the weekly review use. */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** ScheduleKind.representativeHour on iOS, and the hours MojiViewModel writes. */
    fun representativeHour(kind: String): Int? = when (kind) {
        "allDay" -> 0; "morning" -> 9; "afternoon" -> 14; "evening" -> 19; "exactTime" -> null; else -> 0
    }

    fun scheduleName(kind: String): String = when (kind) {
        "allDay" -> "全天"; "morning" -> "上午"; "afternoon" -> "下午"; "evening" -> "晚上"; "exactTime" -> "具体时间"; else -> "全天"
    }

    fun repeatName(rule: String): String = when (rule) {
        "daily" -> "每天"; "weekdays" -> "每个工作日"; "weekly" -> "每周"; "customWeekdays" -> "自选星期"; "monthly" -> "每月"; else -> "不重复"
    }

    /** Same names as `categoryName` in ScreenCommon.kt. */
    fun categoryDisplayName(raw: String): String = when (raw) {
        "study" -> "学习"
        "work" -> "工作"
        else -> raw.removePrefix("custom:").ifBlank { "其他" }
    }

    /** DurationText.full on iOS. */
    fun durationText(minutes: Int): String {
        val safe = maxOf(0, minutes)
        val hours = safe / 60
        val rest = safe % 60
        return when {
            hours == 0 -> "$rest 分钟"
            rest == 0 -> "$hours 小时"
            else -> "$hours 小时 $rest 分钟"
        }
    }

    /** CheckInItem.plannedDurationMinutes on iOS. */
    fun plannedDurationMinutes(plan: CheckInItem): Int? {
        if (plan.kind != "planned") return null
        val enabled = plan.plannedDurationEnabled
            ?: (plan.detailsConfigured != false && (plan.scheduleKind ?: "exactTime") == "exactTime")
        return if (enabled) maxOf(1, plan.plannedMinutes) else null
    }

    /** CarryOverSeal.daysLate on iOS: whole days a plan is late, null when it is not. */
    fun daysLate(scheduledStart: String, now: ZonedDateTime): Int? {
        val planned = instant(scheduledStart)?.atZone(now.zone)?.toLocalDate() ?: return null
        val days = ChronoUnit.DAYS.between(planned, now.toLocalDate())
        return if (days >= 1) days.toInt() else null
    }

    /** Stored dates are ISO instants, sometimes with an offset instead of Z. */
    fun instant(text: String?): Instant? {
        if (text == null) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    private fun summary(snapshot: PlanSnapshot, start: LocalDate, zone: ZoneId, category: String?) =
        PlanWorkflow.summary(
            snapshot,
            start.atStartOfDay(zone).toInstant().toString(),
            start.plusDays(7).atStartOfDay(zone).toInstant().toString(),
            category,
        )

    private val newlines = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    /** Characters as a reader counts them, like Swift's `String.count`. */
    fun characterCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** The first `limit` characters, never splitting one, like Swift's `prefix`. */
    fun prefix(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var end = 0
        repeat(limit) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }
}
