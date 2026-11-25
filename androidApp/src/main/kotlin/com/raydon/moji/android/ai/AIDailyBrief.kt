package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 今日提要 and 顺延旁批: when they show, what they say, and the one request a
 * day the brief may send. Everything here is pure, so the same samples
 * replay in tests. `plans` are today's pending plans in the order the page
 * lists them.
 */
object AIDailyBrief {
    /** A plan carried over at least this many days gets a note under its row. */
    const val carryNoteDays = 3
    /** Countdowns this close are named on the card. */
    const val cardCountdownDays = 14
    /** Countdowns this close go into the request. */
    const val requestCountdownDays = 30
    /** After a failed request, the next try waits this long. */
    const val retrySeconds = 30 * 60L

    data class Countdown(val title: String, val days: Int, val isPinned: Boolean)

    data class Carried(val plan: CheckInItem, val days: Int)

    /** What the model answered, with plan numbers already turned into plan ids. */
    data class Answer(val order: List<String>, val reason: String?)

    /** The text sent and the plans its numbers stand for. */
    data class Request(val input: String, val plans: List<CheckInItem>)

    fun dayKey(day: LocalDate): String = "%04d-%02d-%02d".format(Locale.ROOT, day.year, day.monthValue, day.dayOfMonth)

    /** Countdowns from today on within [within] days, nearest first; a pinned one wins a tie. */
    fun countdowns(events: List<CountdownEvent>, now: ZonedDateTime, within: Int): List<Countdown> {
        val today = now.toLocalDate()
        return events.mapNotNull { event ->
            val days = ChronoUnit.DAYS.between(today, AIAssistant.occurrence(event, now))
            if (days in 0..within.toLong()) Countdown(event.title, days.toInt(), event.isPinned) else null
        }.sortedWith(compareBy<Countdown> { it.days }.thenBy { if (it.isPinned) 0 else 1 })
    }

    /** Plans late by a day or more, the longest first. */
    fun carried(plans: List<CheckInItem>, now: ZonedDateTime): List<Carried> =
        plans.mapNotNull { plan -> AIDrafting.daysLate(plan.scheduledStart, now)?.let { Carried(plan, it) } }
            .sortedByDescending { it.days }

    /** Two plans or more, or one with something pressing about it. */
    fun shouldShow(plans: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime): Boolean = when {
        plans.size >= 2 -> true
        plans.size == 1 -> carried(plans, now).isNotEmpty() || countdowns(events, now, cardCountdownDays).isNotEmpty()
        else -> false
    }

    /** 12 characters at most on the card's first line: the first 11 and “…”. */
    fun shortTitle(title: String): String {
        val line = oneLine(title)
        return if (AIDrafting.characterCount(line) > 12) AIDrafting.prefix(line, 11) + "…" else line
    }

    /** A title on one line: line breaks become spaces; [limit] characters at most when given. */
    internal fun oneLine(title: String, limit: Int? = null): String {
        val line = title.replace(newlines, " ").trim()
        return if (limit == null) line else AIDrafting.prefix(line, limit)
    }

    /** The card's first line, worked out on the device and always current. */
    fun firstLine(plans: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime): String {
        val parts = mutableListOf<String>()
        val lengths = plans.mapNotNull { AIDrafting.plannedDurationMinutes(it) }
        parts.add("今天 ${plans.size} 件" + if (lengths.isEmpty()) "" else "，约 ${AIDrafting.durationText(lengths.sum())}")
        countdowns(events, now, cardCountdownDays).firstOrNull()?.let { countdown ->
            val name = shortTitle(countdown.title)
            parts.add(when (countdown.days) {
                0 -> "「$name」就是今天"
                1 -> "明天就是「$name」"
                else -> "离「$name」还有 ${countdown.days} 天"
            })
        }
        val carried = carried(plans, now)
        if (carried.size == 1) {
            parts.add("「${shortTitle(carried[0].plan.title)}」已顺延 ${carried[0].days} 天")
        } else if (carried.size > 1) {
            parts.add("「${shortTitle(carried[0].plan.title)}」等 ${carried.size} 件已顺延，最久 ${carried[0].days} 天")
        }
        return parts.joinToString("；") + "。"
    }

    /** The plan the cached order puts first among those still pending. */
    fun firstPending(cache: AIProactiveStore.BriefCache?, plans: List<CheckInItem>, today: LocalDate): CheckInItem? {
        if (cache == null || cache.day != dayKey(today)) return null
        val byID = plans.associateBy { it.id }
        return cache.order.firstNotNullOfOrNull { byID[it] }
    }

    /**
     * The second line, from today's cached answer. The reason is kept only
     * while the model's own first pick is still pending; the title is never shortened.
     */
    fun secondLine(cache: AIProactiveStore.BriefCache?, plans: List<CheckInItem>, today: LocalDate): String? {
        val first = firstPending(cache, plans, today) ?: return null
        val reason = cache?.reason?.takeIf { it.isNotEmpty() && cache.order.firstOrNull() == first.id }
        val name = first.title.replace(newlines, " ")
        return if (reason != null) "宜先做「$name」，$reason。" else "宜先做「$name」。"
    }

    /** Today's pending plans in the cached order; plans it does not list keep their order after it. */
    fun sorted(plans: List<CheckInItem>, ids: List<String>): List<CheckInItem> {
        val rank = ids.withIndex().associate { it.value to it.index }
        val listed = plans.filter { it.id in rank }.sortedBy { rank[it.id] }
        return listed + plans.filter { it.id !in rank }
    }

    // Request

    fun instructions(now: ZonedDateTime): String = listOf(
        AIDrafting.preface,
        "任务：替用户排今天计划的先后，并说明为什么先做第一件。" + AIDrafting.todayLine(now),
        "有具体钟点的按钟点先后；和临近日子有关的、顺延多天的优先；不要编造输入里没有的事。",
        """输出 JSON：{"order":[计划编号…],"reason":"…"}""",
        "order 列出全部编号，第一个是建议先做的那件。reason 不超过 24 个字，说为什么先做它，可以提到离哪个日子还有几天、已经顺延几天；不要复述标题，不要夸奖，不要用感叹号。",
    ).joinToString("\n")

    private val newlines = Regex("\r\n|[\n\r\u000B\u000C\u0085  ]")

    private fun timeText(plan: CheckInItem, zone: ZoneId): String {
        val kind = plan.scheduleKind ?: "exactTime"
        if (kind != "exactTime") return AIDrafting.scheduleName(kind)
        val time = AIDrafting.instant(plan.scheduledStart)?.atZone(zone)?.toLocalTime() ?: return AIDrafting.scheduleName("allDay")
        return "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)
    }

    /** [1] 标题（14:30，45 分钟，工作，已顺延 3 天）: one plan as the brief and the evening wrap send it. */
    internal fun planLine(plan: CheckInItem, number: Int, now: ZonedDateTime): String {
        val details = mutableListOf(timeText(plan, now.zone))
        AIDrafting.plannedDurationMinutes(plan)?.let { details.add(AIDrafting.durationText(it)) }
        details.add(AIDrafting.categoryDisplayName(plan.category))
        if (plan.status == "inProgress") details.add("正在进行")
        AIDrafting.daysLate(plan.scheduledStart, now)?.let { details.add("已顺延 $it 天") }
        return "[$number] ${oneLine(plan.title, 60)}（${details.joinToString("，")}）"
    }

    /** 最近的日子：项目交付（还有 2 天）、…; null when no day comes within 30 days. */
    internal fun daysLine(events: List<CountdownEvent>, now: ZonedDateTime): String? {
        val near = countdowns(events, now, requestCountdownDays).take(3)
        if (near.isEmpty()) return null
        return "最近的日子：" + near.joinToString("、") { countdown ->
            "${oneLine(countdown.title, 60)}（${if (countdown.days == 0) "就是今天" else "还有 ${countdown.days} 天"}）"
        }
    }

    /** At most 20 plans, numbered from 1, and up to three days coming within 30 days. */
    fun request(plans: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime): Request {
        val listed = plans.take(20)
        val lines = mutableListOf("今天的计划：")
        listed.forEachIndexed { index, plan -> lines.add(planLine(plan, index + 1, now)) }
        daysLine(events, now)?.let { lines.add(it) }
        return Request(lines.joinToString("\n"), listed)
    }

    /** Plan numbers 1…N, each once; missing plans follow in their own order. Throws when none is usable. */
    fun parse(answer: String, plans: List<CheckInItem>): Answer {
        val values = AIDrafting.`object`(answer)
        val raw = values["order"] as? JsonArray ?: throw AIRequestError.unreadableAnswer
        val numbers = mutableListOf<Int>()
        for (element in raw) {
            val number = AIDrafting.integer(element) ?: continue
            if (number in 1..plans.size && number !in numbers) numbers.add(number)
        }
        if (numbers.isEmpty()) throw AIRequestError.unreadableAnswer
        for (number in 1..plans.size) if (number !in numbers) numbers.add(number)
        var reason = AIDrafting.string(values["reason"])?.replace(newlines, " ")
        while (reason != null && reason.isNotEmpty() && reason.last() in "。.！!") reason = reason.dropLast(1)
        reason = reason?.trim()?.takeIf { it.isNotEmpty() }?.let { AIDrafting.prefix(it, 40) }
        return Answer(numbers.map { plans[it - 1].id }, reason)
    }

    // 顺延旁批

    /** 顺延三天了 … 顺延九十九天了; from 100 days on the number is written in digits. */
    fun carryNoteText(days: Int): String =
        if (days >= 100) "顺延 $days 天了，拆小一点 ›" else "顺延${QuickEntryParser.chineseNumeral(days)}天了，拆小一点 ›"
}

/**
 * What the brief and the carry notes remember on this device, in the AI
 * preferences file (never in snapshots or backups). While the QA fixture is
 * on it is an in-memory store made fresh at each launch.
 */
class AIProactiveStore(private val prefs: AIPreferences) {
    /** Today's answer: plan ids in the suggested order, and why the first one goes first. */
    data class BriefCache(val day: String, val order: List<String>, val reason: String?)

    /** The order the user chose with 按建议排序, for one day. */
    data class TodayOrder(val day: String, val ids: List<String>)

    private val briefState = MutableStateFlow(readBrief())
    private val dismissedState = MutableStateFlow(prefs.getString(dismissedDayKey)?.takeIf { it.isNotEmpty() })
    private val orderState = MutableStateFlow(readOrder())
    private val carryState = MutableStateFlow(readCarry())
    private val shareIntroducedState = MutableStateFlow(readList(shareIntroKey))
    private val nextWeekHandledState = MutableStateFlow(readList(nextWeekOfferKey))

    val brief: StateFlow<BriefCache?> = briefState.asStateFlow()
    /** The day × was tapped on the brief. */
    val dismissedDay: StateFlow<String?> = dismissedState.asStateFlow()
    val todayOrder: StateFlow<TodayOrder?> = orderState.asStateFlow()
    /** Plans whose carry note was answered with 不用. */
    val carryDismissed: StateFlow<List<String>> = carryState.asStateFlow()
    /** Weeks (their Monday, yyyy-MM-dd) whose 下周 offer was closed with × or taken up. */
    val nextWeekHandled: StateFlow<List<String>> = nextWeekHandledState.asStateFlow()

    private val loadingState = MutableStateFlow(false)
    /** True while today's order is being asked for. */
    val loading: StateFlow<Boolean> = loadingState.asStateFlow()

    /** Not tied to a screen, so leaving the page does not cancel the day's request. */
    private val work by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /** Today's cached answer, if there is one for [day]. */
    fun brief(day: LocalDate): BriefCache? = briefState.value?.takeIf { it.day == AIDailyBrief.dayKey(day) }

    fun isDismissed(day: LocalDate): Boolean = dismissedState.value == AIDailyBrief.dayKey(day)

    /** The order chosen with 按建议排序, if it was chosen for [day]. */
    fun order(day: LocalDate): List<String>? = orderState.value?.takeIf { it.day == AIDailyBrief.dayKey(day) }?.ids

    fun isCarryNoteDismissed(planID: String): Boolean = planID in carryState.value

    /**
     * Asks the model once a day for the order of today's plans, while the card
     * is on screen and nothing is cached for today. A failed request waits
     * half an hour; nothing is shown for it.
     */
    fun refreshIfNeeded(pending: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime = ZonedDateTime.now(),
                        settings: AISettingsStore = AISettingsStore.shared) {
        val today = now.toLocalDate()
        if (!settings.isReady || !settings.settings.value.dailyBrief || loadingState.value || pending.isEmpty()) return
        if (isDismissed(today) || brief(today) != null || !AIDailyBrief.shouldShow(pending, events, now)) return
        val seconds = now.toEpochSecond()
        lastAttempt?.let { last -> if (seconds >= last && seconds - last < AIDailyBrief.retrySeconds) return }
        lastAttempt = seconds
        loadingState.value = true
        val plans = pending.take(20)
        val day = AIDailyBrief.dayKey(today)
        work.launch {
            try {
                val answer = AIAssistant(settings).dailyBrief(plans, events, now)
                saveBrief(BriefCache(day, answer.order, answer.reason))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Quiet on purpose: the card keeps its first line.
            } finally {
                loadingState.value = false
            }
        }
    }

    /** Epoch seconds of the last brief request. */
    var lastAttempt: Long?
        get() = prefs.getString(attemptKey)?.toLongOrNull()
        set(value) { if (value == null) prefs.remove(attemptKey) else prefs.putString(attemptKey, value.toString()) }

    fun saveBrief(cache: BriefCache) {
        val text = buildJsonObject {
            put("day", cache.day)
            put("order", buildJsonArray { cache.order.forEach { add(JsonPrimitive(it)) } })
            cache.reason?.let { put("reason", it) }
        }.toString()
        prefs.putString(briefKey, text)
        briefState.value = cache
    }

    fun dismissBrief(day: String) {
        prefs.putString(dismissedDayKey, day)
        dismissedState.value = day
    }

    fun saveTodayOrder(order: TodayOrder?) {
        if (order == null) {
            prefs.remove(todayOrderKey)
        } else {
            prefs.putString(todayOrderKey, buildJsonObject {
                put("day", order.day)
                put("ids", buildJsonArray { order.ids.forEach { add(JsonPrimitive(it)) } })
            }.toString())
        }
        orderState.value = order
    }

    /**
     * Something shared in while not set up opens the introduction once (A4); after
     * that it waits in the inbox without asking again, 隐藏这些入口 or not.
     */
    fun shouldIntroduceShare(itemID: String): Boolean = itemID !in shareIntroducedState.value

    /** Of the inbox's items, oldest first, the first that has not had its introduction yet. */
    fun shareToIntroduce(itemIDs: List<String>): String? = itemIDs.firstOrNull(::shouldIntroduceShare)

    fun markShareIntroduced(itemID: String) {
        if (itemID in shareIntroducedState.value) return
        shareIntroducedState.value = save(shareIntroKey, shareIntroducedState.value + itemID)
    }

    /** The 下周 offer under a saved 周复盘, closed with × or taken up for that week ("2026-11-23") (B3). */
    fun isNextWeekOfferHandled(week: String): Boolean = week in nextWeekHandledState.value

    fun markNextWeekOfferHandled(week: String) {
        if (week !in nextWeekHandledState.value) nextWeekHandledState.value = save(nextWeekOfferKey, nextWeekHandledState.value + week)
    }

    private fun save(key: String, values: List<String>): List<String> {
        val list = values.takeLast(rememberedLimit)
        prefs.putString(key, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }.toString())
        return list
    }

    private fun readList(key: String): List<String> {
        val text = prefs.getString(key)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return strings(runCatching { Json.parseToJsonElement(text) }.getOrNull())
    }

    fun dismissCarryNote(planID: String) {
        if (planID in carryState.value) return
        val list = (carryState.value + planID).takeLast(carryDismissedLimit)
        prefs.putString(carryDismissedKey, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }.toString())
        carryState.value = list
    }

    private fun json(key: String): JsonObject? =
        prefs.getString(key)?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }

    private fun strings(value: Any?): List<String> = (value as? JsonArray).orEmpty().mapNotNull { it.aiString() }

    private fun readBrief(): BriefCache? {
        val values = json(briefKey) ?: return null
        val day = values["day"].aiString() ?: return null
        return BriefCache(day, strings(values["order"]), values["reason"].aiString()?.takeIf { it.isNotEmpty() })
    }

    private fun readOrder(): TodayOrder? {
        val values = json(todayOrderKey) ?: return null
        val day = values["day"].aiString() ?: return null
        return TodayOrder(day, strings(values["ids"]))
    }

    private fun readCarry(): List<String> {
        val text = prefs.getString(carryDismissedKey)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return strings(runCatching { Json.parseToJsonElement(text) }.getOrNull())
    }

    companion object {
        const val briefKey = "moji.ai.brief.v1"
        const val attemptKey = "moji.ai.brief.attempt"
        const val dismissedDayKey = "moji.ai.brief.dismissedDay"
        const val todayOrderKey = "moji.ai.todayOrder.v1"
        const val carryDismissedKey = "moji.ai.carryNotes.dismissed.v1"
        const val carryDismissedLimit = 300
        // Same keys and limit as iOS AISettingsStore: beside the settings, not in the snapshot or backups.
        const val shareIntroKey = "moji.ai.shareIntroShown.v1"
        const val nextWeekOfferKey = "moji.ai.nextWeekOffer.v1"
        const val rememberedLimit = 200

        @Volatile private var installed: AIProactiveStore? = null
        @Volatile private var fixture: AIProactiveStore? = null
        @Volatile private var fallback: AIProactiveStore? = null

        val shared: AIProactiveStore
            get() {
                if (AIQAFixture.isActive) {
                    return fixture ?: synchronized(this) { fixture ?: AIProactiveStore(AIMemoryPreferences()).also { fixture = it } }
                }
                installed?.let { return it }
                return synchronized(this) { fallback ?: AIProactiveStore(AIMemoryPreferences()).also { fallback = it } }
            }

        /** Called with the AI preferences file from `AISettingsStore.install`. */
        fun install(prefs: AIPreferences) {
            if (installed != null) return
            synchronized(this) { if (installed == null) installed = AIProactiveStore(prefs) }
        }

        /** The QA fixture starts each launch with nothing remembered. */
        internal fun resetFixture() {
            synchronized(this) { fixture = null }
        }
    }
}
