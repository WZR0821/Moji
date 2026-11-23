package com.raydon.moji.android.ai

import com.raydon.moji.android.data.LocalRules
import com.raydon.moji.android.data.PlanQuickChange
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.TimeRecord
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Ported from Moji/AI/AIEveningWrap.swift.

/**
 * The clock the evening and Sunday cards read. A debug scenario can stand it
 * at 21:30 or on Sunday evening so those screens can be photographed at any
 * hour; a release build always reads the real time.
 */
object AIClock {
    fun now(): ZonedDateTime = AIQAFixture.clock ?: ZonedDateTime.now()
}

/** One way to deal with a plan left over at the end of the day. */
enum class WrapAction(val raw: String, val title: String) {
    Tomorrow("tomorrow", "顺延到明天"),
    Morning("morning", "明早先做"),
    Split("split", "拆小，明早先做第一步"),
    Drop("drop", "放下"),
    Keep("keep", "留在今天");

    companion object {
        fun of(raw: String?): WrapAction? = entries.firstOrNull { it.raw == raw }
    }
}

/** The first sentence of 收尾, worked out on the device every time it is drawn. */
data class EveningWrapFacts(
    val completed: Int = 0,
    val focusMinutes: Int = 0,
    /** Focus on the days of this week before today, rounded; null on a Monday. */
    val weekAverage: Int? = null,
)

/** What the model said for one plan. */
data class WrapArrangement(val action: WrapAction, val note: String?)

/**
 * 晚间收尾: what is said, what is sent, how the answer is read and what
 * 照此处理 writes. Pure, so the same samples replay in tests.
 */
object AIEveningWrap {
    const val startHour = 21
    const val maximumRows = 6
    const val maximumPlans = 20
    const val maximumDone = 20

    fun isEvening(now: ZonedDateTime): Boolean = now.hour >= startHour

    // Facts

    /**
     * The numbers of the review page's week for today: plans scheduled today
     * that are done, focus from the time records, and the mean of the days
     * of this week before today.
     */
    fun facts(items: List<CheckInItem>, records: List<TimeRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): EveningWrapFacts {
        val completed = items.count { item ->
            item.kind == "planned" && item.status == "completed" &&
                AIDrafting.instant(item.scheduledStart)?.atZone(zone)?.toLocalDate() == today
        }
        val focus = LocalRules.minutes(records, today, today.plusDays(1), zone).toInt()
        val monday = AIDrafting.weekStart(today)
        val before = generateSequence(monday) { it.plusDays(1) }.takeWhile { it < today }
            .map { LocalRules.minutes(records, it, it.plusDays(1), zone) }.toList()
        val average = if (before.isEmpty()) null else (before.sum().toDouble() / before.size).roundToInt()
        return EveningWrapFacts(completed, focus, average)
    }

    /** 今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。 */
    fun factsLine(facts: EveningWrapFacts): String {
        var text = if (facts.completed > 0) "今天完成 ${facts.completed} 件" else "今天还没有完成的计划"
        if (facts.focusMinutes > 0) {
            text += "，专注 ${AIDrafting.durationText(facts.focusMinutes)}"
            val average = facts.weekAverage
            if (average != null && average > 0) {
                val difference = facts.focusMinutes - average
                if (difference >= 10) text += "，比这周平均多 ${AIDrafting.durationText(difference)}"
                else if (difference <= -10) text += "，比这周平均少 ${AIDrafting.durationText(-difference)}"
            }
        }
        return "$text。"
    }

    fun shouldShow(facts: EveningWrapFacts, pending: Int): Boolean = facts.completed >= 1 || pending >= 1

    fun secondLine(applied: Boolean, pending: Int, hasArrangement: Boolean): String = when {
        applied -> "剩下的已经安排好了。"
        pending == 0 -> "今天的计划都做完了。"
        hasArrangement -> "剩下 $pending 件这样安排："
        else -> "剩下 $pending 件没做完。"
    }

    // Asking

    fun instructions(now: ZonedDateTime): String = listOf(
        AIDrafting.preface,
        "任务：现在是晚上，替用户收尾今天：给每件没做完的计划一个安排。" + AIDrafting.todayLine(now),
        "安排只能是四种之一：\"tomorrow\"（顺延到明天，时间不变）、\"morning\"（挪到明天上午先做）、\"split\"（太大或顺延多天的，拆小后明早先做第一步）、\"drop\"（看起来不必再做的，放下）。拿不准时用 \"tomorrow\"；只有输入里看得出不必再做时才用 \"drop\"。不要编造输入里没有的事。",
        """输出 JSON：{"items":[{"index":计划编号,"action":"tomorrow"|"morning"|"split"|"drop","note":"…"}]}""",
        "note 不超过 16 个字，说为什么这样安排，可以提到已经顺延几天、离哪个日子还有几天；不要复述标题，不要夸奖，不要用感叹号。",
    ).joinToString("\n")

    /**
     * What was done today, the plans left (numbered as for 今日提要) and the
     * days coming up. Nothing else is sent.
     */
    fun input(done: List<CheckInItem>, pending: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime): String {
        val lines = mutableListOf<String>()
        if (done.isNotEmpty()) {
            lines.add("今天做完的：")
            for (item in done.take(maximumDone)) {
                lines.add("- ${AIDailyBrief.oneLine(item.title, 60)}（${AIDrafting.categoryDisplayName(item.category)}）")
            }
        }
        lines.add("没做完的：")
        pending.take(maximumPlans).forEachIndexed { index, item -> lines.add(AIDailyBrief.planLine(item, index + 1, now)) }
        AIDailyBrief.daysLine(events, now)?.let { lines.add(it) }
        return lines.joinToString("\n")
    }

    /**
     * Plan number → what to do with it. Numbers outside 1…count, unknown
     * actions and repeated numbers are dropped; nothing usable throws.
     */
    fun arrangement(answer: String, count: Int): Map<Int, WrapArrangement> {
        if (count <= 0) throw AIRequestError.unreadableAnswer
        val values = AIDrafting.`object`(answer)
        val result = linkedMapOf<Int, WrapArrangement>()
        for (raw in (values["items"] as? JsonArray).orEmpty()) {
            val item = raw as? JsonObject ?: continue
            val number = AIDrafting.integer(item["index"]) ?: continue
            if (number !in 1..count || number in result) continue
            val action = WrapAction.of(AIDrafting.string(item["action"])) ?: continue
            if (action == WrapAction.Keep) continue
            result[number] = WrapArrangement(action, note(item["note"]))
        }
        if (result.isEmpty()) throw AIRequestError.unreadableAnswer
        return result
    }

    private val newlines = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    fun note(value: JsonElement?): String? {
        var text = AIDrafting.string(value)?.replace(newlines, " ") ?: return null
        while (text.isNotEmpty() && text.last() in "。.！!") text = text.dropLast(1)
        text = text.trim()
        return if (text.isEmpty()) null else AIDrafting.prefix(text, 24)
    }

    // 照此处理

    data class Outcome(
        val changed: List<CheckInItem> = emptyList(),
        val tomorrow: Int = 0,
        val morning: Int = 0,
        val dropped: Int = 0,
        /** The first plan to break down once the rest is written. */
        val firstSplit: String? = null,
    ) {
        val summary: String get() = AIWriteText.wrap(tomorrow, morning, dropped)
    }

    /**
     * Plans moved to tomorrow keep their time; 明早 and 拆小 move to tomorrow
     * morning with their length; 放下 archives. A plan being timed stays.
     */
    fun apply(plans: List<Pair<CheckInItem, WrapAction>>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Outcome {
        val tomorrow = today.plusDays(1)
        val changed = mutableListOf<CheckInItem>()
        var later = 0
        var morning = 0
        var dropped = 0
        var firstSplit: String? = null
        for ((item, action) in plans) {
            if (item.kind != "planned" || item.status != "planned") continue
            when (action) {
                WrapAction.Tomorrow -> {
                    val moved = PlanQuickChange.Date(tomorrow).applyTo(item, zone) ?: continue
                    changed.add(moved)
                    later += 1
                }
                WrapAction.Morning, WrapAction.Split -> {
                    val moved = PlanQuickChange.Date(tomorrow).applyTo(item, zone) ?: continue
                    val early = PlanQuickChange.Schedule("morning", LocalTime.of(9, 0), AIDrafting.plannedDurationMinutes(item))
                        .applyTo(moved, zone) ?: continue
                    changed.add(early)
                    morning += 1
                    if (action == WrapAction.Split && firstSplit == null) firstSplit = item.id
                }
                WrapAction.Drop -> {
                    changed.add(item.copy(isArchived = true))
                    dropped += 1
                }
                WrapAction.Keep -> Unit
            }
        }
        return Outcome(changed, later, morning, dropped, firstSplit)
    }
}

/**
 * What 晚间收尾 remembers on this device: the evening's answer, the evening
 * the card was closed, the evening it was carried out. In the AI preferences
 * file only; while the QA fixture is on, in memory.
 */
class AIEveningWrapState(private val prefs: AIPreferences) {
    data class Entry(val id: String, val action: WrapAction, val note: String?)

    data class Saved(val day: String, val items: List<Entry>)

    private val savedState = MutableStateFlow(readSaved())
    private val loadingState = MutableStateFlow(false)
    private val dismissedState = MutableStateFlow(prefs.getString(dismissedDayKey)?.takeIf { it.isNotEmpty() })
    private val appliedState = MutableStateFlow(prefs.getString(appliedDayKey)?.takeIf { it.isNotEmpty() })

    val saved: StateFlow<Saved?> = savedState.asStateFlow()
    /** True while this evening's arrangement is being asked for. */
    val loading: StateFlow<Boolean> = loadingState.asStateFlow()
    /** The evening × was tapped on the card. */
    val dismissedDay: StateFlow<String?> = dismissedState.asStateFlow()
    /** The evening 照此处理 was carried out. */
    val appliedDay: StateFlow<String?> = appliedState.asStateFlow()

    /** Not tied to a screen, so leaving the page does not cancel the evening's request. */
    private val work by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    /** Plan id → the saved arrangement for [day]; the first entry for a plan wins. */
    fun arrangement(day: LocalDate): Map<String, Entry> {
        val value = savedState.value?.takeIf { it.day == AIDailyBrief.dayKey(day) } ?: return emptyMap()
        val result = linkedMapOf<String, Entry>()
        for (entry in value.items) if (entry.id !in result) result[entry.id] = entry
        return result
    }

    /** An answer for today that still speaks about a plan left on the list. */
    fun hasArrangement(day: LocalDate, pending: List<CheckInItem>): Boolean {
        val ids = arrangement(day)
        return pending.any { it.id in ids }
    }

    fun isDismissed(day: LocalDate): Boolean = dismissedState.value == AIDailyBrief.dayKey(day)

    fun dismiss(day: LocalDate) {
        val key = AIDailyBrief.dayKey(day)
        prefs.putString(dismissedDayKey, key)
        dismissedState.value = key
    }

    fun isApplied(day: LocalDate): Boolean = appliedState.value == AIDailyBrief.dayKey(day)

    fun markApplied(day: LocalDate) {
        val key = AIDailyBrief.dayKey(day)
        prefs.putString(appliedDayKey, key)
        appliedState.value = key
    }

    /** Epoch seconds of the last request. */
    var lastAttempt: Long?
        get() = prefs.getString(attemptKey)?.toLongOrNull()
        set(value) { if (value == null) prefs.remove(attemptKey) else prefs.putString(attemptKey, value.toString()) }

    fun save(value: Saved) {
        prefs.putString(wrapKey, buildJsonObject {
            put("day", value.day)
            put("items", buildJsonArray {
                for (entry in value.items) add(buildJsonObject {
                    put("id", entry.id)
                    put("action", entry.action.raw)
                    entry.note?.let { put("note", it) }
                })
            })
        }.toString())
        savedState.value = value
    }

    /**
     * Asks once an evening, while the card is on screen, for what to do with
     * each plan left. A failed request waits half an hour, quietly.
     */
    fun refreshIfNeeded(done: List<CheckInItem>, pending: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime,
                        settings: AISettingsStore = AISettingsStore.shared) {
        val today = now.toLocalDate()
        if (!settings.isReady || !settings.settings.value.eveningWrap || loadingState.value || pending.isEmpty()) return
        if (!AIEveningWrap.isEvening(now) || isDismissed(today) || isApplied(today) || arrangement(today).isNotEmpty()) return
        val seconds = now.toEpochSecond()
        lastAttempt?.let { last -> if (seconds >= last && seconds - last < AIDailyBrief.retrySeconds) return }
        lastAttempt = seconds
        loadingState.value = true
        val plans = pending.take(AIEveningWrap.maximumPlans)
        val day = AIDailyBrief.dayKey(today)
        work.launch {
            try {
                val result = AIAssistant(settings).eveningWrap(done, plans, events, now)
                val items = result.entries.sortedBy { it.key }.mapNotNull { (number, value) ->
                    plans.getOrNull(number - 1)?.let { Entry(it.id, value.action, value.note) }
                }
                save(Saved(day, items))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Quiet on purpose: the card keeps its first two lines.
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun readSaved(): Saved? {
        val text = prefs.getString(wrapKey)?.takeIf { it.isNotEmpty() } ?: return null
        val values = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val day = values["day"].aiString() ?: return null
        val items = (values["items"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val id = item["id"].aiString() ?: return@mapNotNull null
            val action = WrapAction.of(item["action"].aiString()) ?: return@mapNotNull null
            Entry(id, action, item["note"].aiString()?.takeIf { it.isNotEmpty() })
        }
        return Saved(day, items)
    }

    companion object {
        const val wrapKey = "moji.ai.wrap.v1"
        const val attemptKey = "moji.ai.wrap.attempt"
        const val dismissedDayKey = "moji.ai.wrap.dismissedDay"
        const val appliedDayKey = "moji.ai.wrap.appliedDay"

        @Volatile private var installed: AIEveningWrapState? = null
        @Volatile private var fixture: AIEveningWrapState? = null
        @Volatile private var fallback: AIEveningWrapState? = null

        val shared: AIEveningWrapState
            get() {
                if (AIQAFixture.isActive) {
                    return fixture ?: synchronized(this) { fixture ?: AIEveningWrapState(AIMemoryPreferences()).also { fixture = it } }
                }
                installed?.let { return it }
                return synchronized(this) { fallback ?: AIEveningWrapState(AIMemoryPreferences()).also { fallback = it } }
            }

        /** Called with the AI preferences file from `AISettingsStore.install`. */
        fun install(prefs: AIPreferences) {
            if (installed != null) return
            synchronized(this) { if (installed == null) installed = AIEveningWrapState(prefs) }
        }

        /** The QA fixture starts each launch with nothing remembered. */
        internal fun resetFixture() {
            synchronized(this) { fixture = null }
        }
    }
}
