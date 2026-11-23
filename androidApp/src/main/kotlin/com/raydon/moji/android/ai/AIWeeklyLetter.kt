package com.raydon.moji.android.ai

import com.raydon.moji.android.data.LocalRules
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Ported from Moji/AI/AIWeeklyLetter.swift.

/** One 本周来信, kept on this device with the sentence of facts it was written under. */
data class WeeklyLetter(
    /** The Monday of the week, yyyy-MM-dd. */
    val week: String,
    val title: String,
    val body: String,
    val nextFocus: String,
    val facts: String,
    val evidence: List<Evidence>,
    /** Seconds since 1970. */
    val createdAt: Double,
) {
    data class Evidence(
        val point: String,
        /** Titles of the plans the point rests on, looked up locally. */
        val plans: List<String>,
    )
}

/**
 * 本周来信: which week, what is said on the device, what is sent and how the
 * answer is read. Pure, so the same samples replay in tests.
 */
object AIWeeklyLetter {
    /** Sunday at this hour the week counts as over. */
    const val hour = 20
    const val minimumPlans = 3
    const val limit = 52
    const val notificationIdentifier = "moji.ai.weeklyLetter"

    /** Sunday 20:00 of the week containing [date]. */
    fun letterMoment(date: ZonedDateTime): ZonedDateTime =
        AIDrafting.weekStart(date.toLocalDate()).plusDays(6).atTime(hour, 0).atZone(date.zone)

    /** The Monday of the week the letter is about: this week from Sunday 20:00 on, the week before until then. */
    fun letterWeek(now: ZonedDateTime): LocalDate {
        val monday = AIDrafting.weekStart(now.toLocalDate())
        return if (!now.isBefore(letterMoment(now))) monday else monday.minusDays(7)
    }

    /** The next Sunday 20:00 strictly after [now]. */
    fun nextNotification(now: ZonedDateTime): ZonedDateTime {
        val moment = letterMoment(now)
        return if (now.isBefore(moment)) moment else moment.plusDays(7)
    }

    /** Sunday 20:00 to Tuesday 0:00, when the teaser may stand on the page. */
    fun isTeaserTime(now: ZonedDateTime): Boolean {
        val moment = letterWeek(now).plusDays(6).atTime(hour, 0).atZone(now.zone)
        return !now.isBefore(moment) && now.isBefore(moment.plusHours(28))
    }

    /** Sunday 12:00–20:00, when the letter is written ahead of its notification. */
    fun isPreparingTime(now: ZonedDateTime): Boolean {
        val moment = letterMoment(now)
        return !now.isBefore(moment.minusHours(8)) && now.isBefore(moment)
    }

    fun weekKey(monday: LocalDate): String = AIDailyBrief.dayKey(AIDrafting.weekStart(monday))

    /** 11月16日 – 22日 (the month again when the week crosses one). */
    fun rangeText(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        val tail = if (monday.monthValue == sunday.monthValue) "${sunday.dayOfMonth}日" else "${sunday.monthValue}月${sunday.dayOfMonth}日"
        return "${monday.monthValue}月${monday.dayOfMonth}日 – $tail"
    }

    fun monday(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()

    // Facts

    data class Facts(val planned: Int = 0, val completed: Int = 0, val focusMinutes: Int = 0, val previousFocusMinutes: Int = 0)

    /** The same numbers as 本周回顾 for that week, and the focus of the week before. */
    fun facts(items: List<CheckInItem>, records: List<TimeRecord>, monday: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Facts {
        val start = AIDrafting.weekStart(monday)
        val end = start.plusDays(7)
        val plans = items.filter { item ->
            val day = AIDrafting.instant(item.scheduledStart)?.atZone(zone)?.toLocalDate()
            item.kind == "planned" && day != null && day >= start && day < end
        }
        return Facts(
            planned = plans.size,
            completed = plans.count { it.status == "completed" },
            focusMinutes = LocalRules.minutes(records, start, end, zone).toInt(),
            previousFocusMinutes = LocalRules.minutes(records, start.minusDays(7), start, zone).toInt(),
        )
    }

    /** 这周完成 9/14，专注 6 小时 20 分钟，比上周多 1 小时。 */
    fun factsLine(facts: Facts): String {
        var text = "这周完成 ${facts.completed}/${facts.planned}"
        if (facts.focusMinutes > 0) {
            text += "，专注 ${AIDrafting.durationText(facts.focusMinutes)}"
            if (facts.previousFocusMinutes > 0) {
                val difference = facts.focusMinutes - facts.previousFocusMinutes
                if (difference >= 10) text += "，比上周多 ${AIDrafting.durationText(difference)}"
                else if (difference <= -10) text += "，比上周少 ${AIDrafting.durationText(-difference)}"
            }
        }
        return "$text。"
    }

    // Asking

    val instructions: String = listOf(
        AIDrafting.preface,
        "任务：一周结束了，替用户写一封“本周来信”：一个题签和两三句这一周的情况。只依据记录里的事实；数字只能照抄记录，不要推算新的数字；不评价用户的为人，不夸奖，不用感叹号，不用“你”。",
        "要讲跨天的事：哪几天顺、哪几天卡住，哪些计划一直顺延、卡在哪一步；不要逐条复述记录。",
        """输出 JSON：{"title":"不超过 10 个字的题签","body":"两三句，不超过 80 字","next_focus":"给下周的一句提醒，不超过 40 字","evidence":[{"point":"不超过 20 字的一句依据","plans":[计划编号]}]}""",
        "evidence 给 1 到 3 条，编号只能用记录里出现过的。",
    ).joinToString("\n")

    data class Draft(val title: String, val body: String, val nextFocus: String, val evidence: List<WeeklyLetter.Evidence>)

    private const val titleMarks = "「」『』“”\"'《》"
    private val newlines = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    private fun flat(text: String): String = text.replace(newlines, " ").trim()

    fun letter(answer: String, plans: List<CheckInItem>): Draft {
        val values = AIDrafting.`object`(answer)
        val rawTitle = AIDrafting.string(values["title"]) ?: throw AIRequestError.unreadableAnswer
        val title = AIDrafting.prefix(flat(rawTitle.trim { it in titleMarks || it.isWhitespace() }), 12)
        if (title.isEmpty()) throw AIRequestError.unreadableAnswer
        val body = AIDrafting.string(values["body"])?.let(::flat)?.takeIf { it.isNotEmpty() } ?: throw AIRequestError.unreadableAnswer
        val evidence = (values["evidence"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val point = AIDrafting.string(item["point"]) ?: return@mapNotNull null
            val titles = (item["plans"] as? JsonArray).orEmpty().mapNotNull { AIDrafting.integer(it) }
                .filter { it >= 1 && it <= plans.size }.map { plans[it - 1].title }
            if (titles.isEmpty()) return@mapNotNull null
            WeeklyLetter.Evidence(AIDrafting.prefix(flat(point), 40), titles.take(4))
        }
        return Draft(title, AIDrafting.prefix(body, 120),
            AIDrafting.prefix(AIDrafting.string(values["next_focus"])?.let(::flat) ?: "", 60), evidence.take(3))
    }

    /** 依据：「归纳上周待办」顺延 4 次 · 「准备季度汇报」还没开始 */
    fun evidenceLine(evidence: List<WeeklyLetter.Evidence>): String? =
        if (evidence.isEmpty()) null else "依据：" + evidence.joinToString(" · ") { it.point }

    /** The letter as it goes into 周复盘's draft area. */
    fun reflectionDraft(letter: WeeklyLetter): ReflectionDraft =
        ReflectionDraft(letter.facts + letter.body, letter.nextFocus, letter.evidence.map { ReflectionDraft.Evidence(it.point, it.plans) })

    fun notificationBody(title: String?): String =
        if (!title.isNullOrEmpty()) "这周的来信：「$title」" else "这一周过完了，打开回顾看看墨记的来信。"
}

/**
 * The letters kept on this device (at most 52, one a week), the week whose
 * letter was put away, and the request in flight. In the AI preferences file
 * only; while the QA fixture is on, in memory.
 */
class AIWeeklyLetterState(private val prefs: AIPreferences) {
    private val lettersState = MutableStateFlow(decode(prefs.getString(lettersKey)))
    private val loadingState = MutableStateFlow<String?>(null)
    private val dismissedState = MutableStateFlow(prefs.getString(dismissedWeekKey)?.takeIf { it.isNotEmpty() })
    private val notifyAskedState = MutableStateFlow(prefs.getString(notifyAskedKey) == "1")

    /** Oldest first, as stored. */
    val letters: StateFlow<List<WeeklyLetter>> = lettersState.asStateFlow()
    val loadingWeek: StateFlow<String?> = loadingState.asStateFlow()
    val dismissedWeek: StateFlow<String?> = dismissedState.asStateFlow()
    /** 开启提醒 was tapped once; Android asks for notifications only from there. */
    val notifyAsked: StateFlow<Boolean> = notifyAskedState.asStateFlow()

    /** Not tied to a screen, so leaving the page does not cancel the request. */
    private val work by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    val newestFirst: List<WeeklyLetter> get() = lettersState.value.sortedByDescending { it.week }

    fun letter(monday: LocalDate): WeeklyLetter? {
        val key = AIWeeklyLetter.weekKey(monday)
        return lettersState.value.firstOrNull { it.week == key }
    }

    fun isDismissed(monday: LocalDate): Boolean = dismissedState.value == AIWeeklyLetter.weekKey(monday)

    fun dismiss(monday: LocalDate) {
        val key = AIWeeklyLetter.weekKey(monday)
        prefs.putString(dismissedWeekKey, key)
        dismissedState.value = key
    }

    fun isLoading(monday: LocalDate): Boolean = loadingState.value == AIWeeklyLetter.weekKey(monday)

    fun markNotifyAsked() {
        prefs.putString(notifyAskedKey, "1")
        notifyAskedState.value = true
    }

    /** Epoch seconds of the last request. */
    var lastAttempt: Long?
        get() = prefs.getString(attemptKey)?.toLongOrNull()
        set(value) { if (value == null) prefs.remove(attemptKey) else prefs.putString(attemptKey, value.toString()) }

    fun store(letter: WeeklyLetter) {
        val list = merged(lettersState.value, letter)
        prefs.putString(lettersKey, encode(list))
        lettersState.value = list
    }

    /** Whether a request for that week would go out now. */
    fun canAsk(monday: LocalDate, snapshot: PlanSnapshot, now: ZonedDateTime, settings: AISettingsStore = AISettingsStore.shared): Boolean {
        if (!settings.isReady || !settings.settings.value.weeklyLetter || loadingState.value != null || letter(monday) != null) return false
        val facts = AIWeeklyLetter.facts(snapshot.checkInItems, snapshot.records, monday, now.zone)
        if (facts.planned < AIWeeklyLetter.minimumPlans) return false
        val seconds = now.toEpochSecond()
        lastAttempt?.let { last -> if (seconds >= last && seconds - last < AIDailyBrief.retrySeconds) return false }
        return true
    }

    /**
     * Writes the letter for that week when nothing is kept for it yet. A
     * failed request waits half an hour; nothing is shown for it.
     */
    fun refreshIfNeeded(monday: LocalDate, snapshot: PlanSnapshot, now: ZonedDateTime, settings: AISettingsStore = AISettingsStore.shared) {
        if (!canAsk(monday, snapshot, now, settings)) return
        lastAttempt = now.toEpochSecond()
        val key = AIWeeklyLetter.weekKey(monday)
        loadingState.value = key
        val facts = AIWeeklyLetter.factsLine(AIWeeklyLetter.facts(snapshot.checkInItems, snapshot.records, monday, now.zone))
        work.launch {
            try {
                val draft = AIAssistant(settings).weeklyLetter(snapshot, monday, now)
                store(WeeklyLetter(key, draft.title, draft.body, draft.nextFocus, facts, draft.evidence,
                    Instant.now().toEpochMilli() / 1000.0))
                reschedule()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Quiet on purpose: the card stays away until the next try.
            } finally {
                loadingState.value = null
            }
        }
    }

    /**
     * Sunday afternoon, so the 20:00 notification can carry the title. The
     * request runs in this store's own scope, so it goes on while the app is
     * in the background as long as Android keeps the process.
     */
    fun prepareIfNeeded(snapshot: PlanSnapshot, now: ZonedDateTime) {
        if (!AIWeeklyLetter.isPreparingTime(now)) return
        refreshIfNeeded(AIDrafting.weekStart(now.toLocalDate()), snapshot, now)
    }

    /** Three earlier letters for the photographed archive. */
    fun seedArchiveForQA(now: ZonedDateTime) {
        val current = AIWeeklyLetter.letterWeek(now)
        val samples = listOf(
            listOf("顺延集中在周四", "这周完成 10/14，专注 6 小时 50 分钟，比上周少 15 分钟。", "周一到周三按计划推进；周四的三件都挪到了周末，周日补完两件。"),
            listOf("前紧后松", "这周完成 11/13，专注 7 小时 5 分钟，比上周多 1 小时 45 分钟。", "周一到周三排得很满，周末留了空；顺延的只有一件。"),
            listOf("复习按天排开", "这周完成 8/12，专注 5 小时 20 分钟，比上周少 40 分钟。", "复习按天排开后每天都有进展；周五的两件挪到了周末。"),
        )
        samples.forEachIndexed { index, (title, facts, body) ->
            val monday = current.minusDays(7L * (index + 1))
            store(WeeklyLetter(AIWeeklyLetter.weekKey(monday), title, body, "先把大事拆出能马上开始的第一步。", facts,
                listOf(WeeklyLetter.Evidence("「归纳上周待办」一直顺延", listOf("归纳上周待办")),
                    WeeklyLetter.Evidence("大事还没开始", listOf("准备季度汇报"))),
                monday.atStartOfDay(now.zone).toInstant().toEpochMilli() / 1000.0 + 6 * 86_400 + 20 * 3_600))
        }
    }

    companion object {
        const val lettersKey = "moji.ai.letters.v1"
        const val attemptKey = "moji.ai.letter.attempt"
        const val dismissedWeekKey = "moji.ai.letter.dismissedWeek"
        /** Android only: 开启提醒 was tapped, so the row does not come back. */
        const val notifyAskedKey = "moji.ai.letter.notifyAsked"

        /** Set by `AISettingsStore.install`: puts the Sunday 20:00 notification right after a change. */
        @Volatile var rescheduler: (() -> Unit)? = null

        fun reschedule() {
            rescheduler?.invoke()
        }

        /** Keeps one letter a week, the newest 52. */
        fun merged(letters: List<WeeklyLetter>, letter: WeeklyLetter): List<WeeklyLetter> {
            val list = (letters.filter { it.week != letter.week } + letter).sortedBy { it.week }
            return if (list.size > AIWeeklyLetter.limit) list.drop(list.size - AIWeeklyLetter.limit) else list
        }

        fun encode(letters: List<WeeklyLetter>): String = buildJsonArray {
            for (letter in letters) add(buildJsonObject {
                put("week", letter.week)
                put("title", letter.title)
                put("body", letter.body)
                put("nextFocus", letter.nextFocus)
                put("facts", letter.facts)
                put("evidence", buildJsonArray {
                    for (item in letter.evidence) add(buildJsonObject {
                        put("point", item.point)
                        put("plans", buildJsonArray { item.plans.forEach { add(JsonPrimitive(it)) } })
                    })
                })
                put("createdAt", letter.createdAt)
            })
        }.toString()

        fun decode(text: String?): List<WeeklyLetter> {
            val array = text?.takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
                ?: return emptyList()
            return array.mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val week = item["week"].aiString() ?: return@mapNotNull null
                val title = item["title"].aiString() ?: return@mapNotNull null
                val evidence = (item["evidence"] as? JsonArray).orEmpty().mapNotNull { value ->
                    val entry = value as? JsonObject ?: return@mapNotNull null
                    val point = entry["point"].aiString() ?: return@mapNotNull null
                    WeeklyLetter.Evidence(point, (entry["plans"] as? JsonArray).orEmpty().mapNotNull { it.aiString() })
                }
                WeeklyLetter(week, title, item["body"].aiString() ?: "", item["nextFocus"].aiString() ?: "",
                    item["facts"].aiString() ?: "", evidence,
                    (item["createdAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0)
            }.sortedBy { it.week }
        }

        @Volatile private var installed: AIWeeklyLetterState? = null
        @Volatile private var fixture: AIWeeklyLetterState? = null
        @Volatile private var fallback: AIWeeklyLetterState? = null

        val shared: AIWeeklyLetterState
            get() {
                if (AIQAFixture.isActive) {
                    return fixture ?: synchronized(this) { fixture ?: AIWeeklyLetterState(AIMemoryPreferences()).also { fixture = it } }
                }
                installed?.let { return it }
                return synchronized(this) { fallback ?: AIWeeklyLetterState(AIMemoryPreferences()).also { fallback = it } }
            }

        /** Called with the AI preferences file from `AISettingsStore.install`. */
        fun install(prefs: AIPreferences) {
            if (installed != null) return
            synchronized(this) { if (installed == null) installed = AIWeeklyLetterState(prefs) }
        }

        /** The QA fixture starts each launch with nothing remembered. */
        internal fun resetFixture() {
            synchronized(this) { fixture = null }
        }
    }
}
