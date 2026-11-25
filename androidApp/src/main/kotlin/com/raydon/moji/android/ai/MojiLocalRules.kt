package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.MemoChecklistItem
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.PlanSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

// 1.10.0 (spec 2026-11-26): rules worked out on the device. Pure, so the same
// samples replay in MojiAI110Tests and in the iOS tests of the same name.

// MARK: - A8 周复盘草稿的数字

/**
 * Numbers in a drafted 周复盘 may only be ones the week's record already has.
 * A sentence that names any other number is taken out whole.
 */
object ReflectionNumbers {
    private val arabic = Regex("[0-9]+(?:\\.[0-9]+)?")
    // No 分 on its own: 十分顺利 is “very smooth”, not ten minutes.
    private val chinese = Regex("[零一二两三四五六七八九十百]+(?=件|项|条|次|天|个|周|小时|分钟)")
    private const val sentenceEnds = "。！？；!?;\n"

    /** Digit runs (with a decimal point) and Chinese numerals right before 件、项、条、次、天、个、周、小时、分钟. */
    fun numbers(text: String): List<Double> {
        val found = arabic.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toMutableList()
        for (match in chinese.findAll(text)) QuickEntryParser.number(match.value)?.let { found.add(it.toDouble()) }
        return found
    }

    /**
     * Every number the record sent to the model has (its facts and the plans'
     * titles; notes are not sent), and 1 to 7.
     */
    fun allowed(record: String): Set<Double> =
        // The [1] [2] … that number the plans are not facts of the week.
        numbers(record.replace(planNumbers, "")).toSet() + (1..7).map { it.toDouble() }

    private val planNumbers = Regex("\\[[0-9]+\\]")

    /** Sentences, each with the mark that ends it. */
    fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (character in text) {
            current.append(character)
            if (character in sentenceEnds) { result.add(current.toString()); current.clear() }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /** [text] without the sentences that name a number outside [allowed]. */
    fun keepingKnown(text: String, allowed: Set<Double>): String =
        sentences(text).filter { sentence -> numbers(sentence).all { it in allowed } }.joinToString("").trim()
}

// MARK: - B2 依据点开

object ReflectionEvidenceText {
    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 11月18日 周三 */
    fun monthDayWeekday(day: LocalDate): String = "${day.monthValue}月${day.dayOfMonth}日 ${weekdays[day.dayOfWeek.value - 1]}"

    /** 已完成、已跳过、顺延 N 天, or 没做完 for a plan still open on its own day. */
    fun status(plan: CheckInItem, now: ZonedDateTime): String = when (plan.status) {
        "completed" -> "已完成"
        "skipped" -> "已跳过"
        else -> AIDrafting.daysLate(plan.scheduledStart, now)?.let { "顺延 $it 天" } ?: "没做完"
    }

    /** 「<标题> · <M月d日 周X> · <状态>」 */
    fun line(plan: CheckInItem, now: ZonedDateTime): String {
        val date = AIDrafting.instant(plan.scheduledStart)?.atZone(now.zone)?.toLocalDate()?.let(::monthDayWeekday) ?: ""
        return listOf(plan.title, date, status(plan, now)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

// MARK: - A1 整理成清单不再丢原文

/** The memo editor's text when a checklist keeps the words it was made from (iOS MemoTextRules). */
object MemoTextRules {
    /** What a checklist saves as its 原文: the text without outer spaces, empty when there is none. */
    fun checklistContent(content: String): String = content.trim()

    /** 转为正文 by hand: the items as lines, and the 原文 after an empty line. */
    fun noteText(items: List<MemoChecklistItem>, original: String): String {
        val lines = items.joinToString("\n") { "${if (it.isCompleted) "✓" else "•"} ${it.text}" }
        val kept = original.trim()
        if (kept.isEmpty()) return lines
        return if (lines.isEmpty()) kept else lines + "\n\n" + kept
    }
}

/**
 * What the memo editor holds. A checklist drafted from the text keeps that
 * text as 原文 in [content]; a checklist made by hand has none.
 */
data class MemoEditState(val checklist: Boolean, val content: String, val items: List<MemoChecklistItem>) {
    /** The text a drafted checklist came from, while there is one. */
    val original: String? get() = if (checklist && content.isNotBlank()) content else null

    /** 用这份清单: the items become the draft's; the text stays, as 原文. */
    fun usingChecklistDraft(drafted: List<String>, newId: () -> String = { UUID.randomUUID().toString() }): MemoEditState {
        if (drafted.isEmpty()) return this
        return copy(checklist = true, items = drafted.map { MemoChecklistItem(newId(), it) })
    }

    /** 转回正文: the checklist goes, 原文 is the text again. */
    fun turnedBackToText(newId: () -> String = { UUID.randomUUID().toString() }): MemoEditState =
        copy(checklist = false, items = listOf(MemoChecklistItem(newId())))

    /** 删去原文 */
    fun droppingOriginal(): MemoEditState = copy(content = "")

    /** 转为正文 by hand: the items as lines, and 原文 after an empty line. */
    fun checklistAsText(newId: () -> String = { UUID.randomUUID().toString() }): MemoEditState {
        val cleaned = items.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.trim()) }
        return copy(checklist = false, content = MemoTextRules.noteText(cleaned, content), items = listOf(MemoChecklistItem(newId())))
    }

    /** The memo as saved: a checklist keeps its 原文. */
    fun memo(id: String, title: String, isPinned: Boolean, createdAt: String, updatedAt: String): MemoItem = MemoItem(
        id = id,
        title = title,
        content = if (checklist) MemoTextRules.checklistContent(content) else content,
        mode = if (checklist) "checklist" else "note",
        checklistItems = if (checklist) items.filter { it.text.isNotBlank() } else emptyList(),
        isPinned = isPinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

// MARK: - A2 过期建议作废

/** What a plan said when a suggestion was made for it; a suggestion for a plan changed since is dropped. */
data class EntryStamp(
    val title: String,
    val status: String,
    val day: LocalDate?,
    val scheduleKind: String,
    val hour: Int,
    val minute: Int,
    val minutes: Int?,
    val category: String,
    val repeatRule: String,
) {
    companion object {
        fun of(plan: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): EntryStamp {
            val start = AIDrafting.instant(plan.scheduledStart)?.atZone(zone)
            val kind = plan.scheduleKind ?: "exactTime"
            val exact = kind == "exactTime"
            return EntryStamp(plan.title, plan.status, start?.toLocalDate(), kind,
                if (exact) start?.hour ?: 0 else 0, if (exact) start?.minute ?: 0 else 0,
                AIDrafting.plannedDurationMinutes(plan), plan.category, plan.repeatRule ?: "never")
        }
    }
}

/** A suggestion under a row with what the plan said then; [isLocal] when read on the device. */
data class EntrySuggestionRecord(val suggestion: EntrySuggestion, val stamp: EntryStamp, val isLocal: Boolean) {
    fun isCurrent(plan: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): Boolean = EntryStamp.of(plan, zone) == stamp
}

/**
 * 拆成 N 条: the first part renames the plan, the rest are new plans, and every
 * field the suggestion read (day, time, length, type, repeat) goes on all of them.
 * Null when there are not two parts.
 */
fun EntrySuggestion.split(
    plan: CheckInItem,
    /** 墨记's mark on the new parts; the renamed plan keeps its own. */
    origin: String? = AIWriteText.splitOrigin(plan.title),
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    newId: () -> String = { UUID.randomUUID().toString() },
): Pair<CheckInItem, List<CheckInItem>>? {
    if (parts.size < 2 || plan.kind != "planned") return null
    val fields = copy(title = null, parts = emptyList())
    fun withFields(item: CheckInItem) = fields.applied(item, zone) ?: item
    val renamed = withFields(plan.copy(title = parts[0]))
    val rest = parts.drop(1).map { part ->
        withFields(CheckInItem(id = newId(), title = part, category = plan.category, scheduledStart = plan.scheduledStart,
            plannedMinutes = plan.plannedMinutes, createdAt = now.toString(), scheduleKind = plan.scheduleKind,
            detailsConfigured = plan.detailsConfigured, plannedDurationEnabled = plan.plannedDurationEnabled,
            aiOrigin = origin))
    }
    return renamed to rest
}

// MARK: - B1 拆成步骤的三个去向

enum class BreakdownDestination(val label: String) {
    Plans("加成计划"), Note("写进说明"), Checklist("存成清单");

    /** The sheet's confirm button for [count] ticked steps. */
    fun confirmTitle(count: Int): String = when (this) {
        Plans -> if (count == 0) "加入" else "加入 $count 条"
        Note -> "写进说明"
        Checklist -> "存成清单"
    }

    /** The line above the steps, saying where the ticked ones go. */
    val draftHint: String get() = when (this) {
        Plans -> "这些是草稿，勾选的才会加入计划。"
        Note -> "这些是草稿，勾选的才会写进说明。"
        Checklist -> "这些是草稿，勾选的才会存进清单。"
    }
}

object BreakdownText {
    /** The plan's note with the steps after it: 「步骤：」 then 「1. …」, one a line, after an empty line. */
    fun noteWithSteps(note: String, steps: List<String>): String {
        val list = "步骤：\n" + steps.mapIndexed { index, step -> "${index + 1}. ${step.trim()}" }.joinToString("\n")
        val kept = note.trim()
        return if (kept.isEmpty()) list else kept + "\n\n" + list
    }

    /** A checklist memo named after the plan, one item per step. */
    fun checklistMemo(title: String, steps: List<String>, now: Instant = Instant.now(),
                      newId: () -> String = { UUID.randomUUID().toString() }): MemoItem = MemoItem(
        id = newId(), title = title, content = "", mode = "checklist",
        checklistItems = steps.map { MemoChecklistItem(newId(), it.trim()) },
        createdAt = now.toString(), updatedAt = now.toString(),
    )
}

// MARK: - N1 预计时长默认值取同类历史用时

object PlanDurationHistory {
    const val days = 60L
    const val samples = 10
    const val minimumSamples = 3

    /**
     * The usual length of a [category] plan: the median of the time recorded on
     * the last ten finished in the past 60 days (three at least), to 5 minutes,
     * 5…240. Null when there is not enough to go on.
     */
    fun minutes(category: String, snapshot: PlanSnapshot, now: Instant = Instant.now()): Int? {
        val from = now.minus(days, ChronoUnit.DAYS)
        val recorded = snapshot.records.groupBy { it.checkInItemID }
        val finished = snapshot.checkInItems.mapNotNull { plan ->
            if (plan.kind != "planned" || plan.category != category || plan.status != "completed") return@mapNotNull null
            val done = AIDrafting.instant(plan.completedAt ?: plan.actualEndDate) ?: return@mapNotNull null
            if (done.isBefore(from) || done.isAfter(now)) return@mapNotNull null
            val seconds = recorded[plan.id].orEmpty().sumOf { record ->
                val start = AIDrafting.instant(record.startDate); val end = AIDrafting.instant(record.endDate)
                if (start == null || end == null) 0L else maxOf(0L, end.epochSecond - start.epochSecond)
            }
            if (seconds <= 0) null else done to seconds / 60.0
        }.sortedByDescending { it.first }.take(samples).map { it.second }
        if (finished.size < minimumSamples) return null
        val sorted = finished.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        // Halves go up, like Swift's rounded().
        val rounded = (floor(median / 5 + 0.5) * 5).toInt()
        return rounded.coerceIn(5, 240)
    }

    /** 按以往的<类型名>计划，约 <N> 分钟 */
    fun footnote(category: String, minutes: Int): String = "按以往的${AIDrafting.categoryDisplayName(category)}计划，约 $minutes 分钟"
}

// MARK: - N2 回顾页「下一步」带顺延最久的那条

object ReviewNextStep {
    /**
     * Of today's open plans in list order, the one still waiting (not being timed)
     * that was carried over longest; the first of them on a tie. Null when none is carried.
     */
    fun carried(pending: List<CheckInItem>, now: ZonedDateTime): Pair<CheckInItem, Int>? {
        var best: Pair<CheckInItem, Int>? = null
        for (plan in pending) {
            if (plan.kind != "planned" || plan.status != "planned") continue
            val days = AIDrafting.daysLate(plan.scheduledStart, now) ?: continue
            if (days < 1) continue
            if (best == null || days > best.second) best = plan to days
        }
        return best
    }

    /** 下一步：先做「<短标题>」（顺延 N 天） */
    fun text(title: String, days: Int): String = "下一步：先做「${AIDailyBrief.shortTitle(title)}」（顺延 $days 天）"
}

// MARK: - N3 重复手写的计划提示存为模板

object TemplateHint {
    const val days = 60L
    const val minimumEarlier = 2

    private val spaces = Regex("[\\s\\u3000]+")

    /** Trimmed, runs of spaces as one, English without case. */
    fun normalized(title: String): String = title.trim().replace(spaces, " ").lowercase(Locale.ROOT)

    /**
     * How many times this title was written in the last 60 days, archived ones
     * included and [plan] counted, when that is three or more, no template has
     * the title and it was not put away. Only for a plan written by hand; occurrences a
     * repeat made are not counted, and a title one repeating plan already has gets no offer.
     */
    fun count(plan: CheckInItem, snapshot: PlanSnapshot, dismissed: Set<String>, handWritten: Boolean, now: Instant = Instant.now()): Int? {
        if (!handWritten || plan.kind != "planned") return null
        val key = normalized(plan.title)
        if (key.isEmpty() || key in dismissed) return null
        if (snapshot.planTemplates.any { normalized(it.title) == key }) return null
        val from = now.minus(days, ChronoUnit.DAYS)
        val sameTitle = snapshot.checkInItems.filter { other ->
            other.id != plan.id && other.kind == "planned" && normalized(other.title) == key
        }
        // A repeating plan already brings it back by itself: no template to suggest.
        if (sameTitle.any { (it.repeatRule ?: "never") != "never" }) return null
        // Occurrences a repeat made were not written by hand.
        val earlier = sameTitle.count { other ->
            other.generatedFromOccurrenceID == null &&
                (AIDrafting.instant(other.createdAt) ?: AIDrafting.instant(other.scheduledStart))?.let { !it.isBefore(from) } == true
        }
        return if (earlier >= minimumEarlier) earlier + 1 else null
    }

    /** 这件事写过 <总次数> 次了，存成模板，下次一点就有 */
    fun text(count: Int): String = "这件事写过 $count 次了，存成模板，下次一点就有"

    const val dismissedLimit = 300

    /** The titles waved away, oldest first, with [title] added once (normalized), at most 300. */
    fun dismissing(list: List<String>, title: String, limit: Int = dismissedLimit): List<String> {
        val key = normalized(title)
        if (key.isEmpty() || key in list) return list
        return (list + key).takeLast(limit)
    }
}

// MARK: - N4 倒数日图标按标题关键词

object CountdownSymbols {
    const val countdownDefault = "hourglass"
    const val anniversaryDefault = "seal"

    /** First match wins, in this order. */
    val table: List<Pair<List<String>, String>> = listOf(
        listOf("生日", "寿") to "gift",
        listOf("考试", "考研", "笔试", "测验", "期末", "期中") to "pencil.and.outline",
        listOf("面试") to "person.2",
        listOf("旅行", "旅游", "出发", "航班", "机票", "出国") to "airplane",
        listOf("交付", "上线", "发布", "截止", "提交", "ddl") to "flag.fill",
        listOf("纪念", "结婚", "恋爱", "在一起") to "heart.fill",
        listOf("毕业") to "graduationcap",
        listOf("开学", "入学", "报到") to "book",
        listOf("搬家") to "house",
        listOf("体检", "复查", "医院", "手术") to "cross.case",
        listOf("演唱会", "音乐会", "演出") to "music.note",
        listOf("比赛", "马拉松", "决赛") to "figure.run",
        listOf("春节", "过年", "新年", "元旦") to "fireworks",
    )

    /** The symbol a title asks for, or null. */
    fun matched(title: String): String? {
        val text = title.lowercase(Locale.ROOT)
        return table.firstOrNull { (words, _) -> words.any { text.contains(it) } }?.second
    }

    /** The defaults of 倒数日 and 纪念日: a day still wearing one follows its title. */
    val defaults: Set<String> = setOf(countdownDefault, anniversaryDefault)

    fun defaultSymbol(past: Boolean): String = if (past) anniversaryDefault else countdownDefault

    fun isDefault(symbol: String): Boolean = symbol in defaults

    /** The title's symbol, or [fallback]. */
    fun withFallback(title: String, fallback: String): String = matched(title) ?: fallback

    /**
     * The symbol to save: a new day takes its title's; one being edited follows
     * its title only when the title changed and it still has a type's default.
     */
    fun symbol(title: String, past: Boolean, existing: String? = null, existingTitle: String? = null): String = when {
        existing == null -> withFallback(title, defaultSymbol(past))
        isDefault(existing) && title.trim() != existingTitle?.trim() -> withFallback(title, existing)
        else -> existing
    }
}
