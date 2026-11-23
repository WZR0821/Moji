package com.raydon.moji.android.ai

import android.content.Context
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.CountdownPolicy
import com.raydon.moji.core.PlanSnapshot
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException

/**
 * Everything Moji asks a model for, one function per place it is used. Each
 * function sends one request and returns a draft: nothing here writes to the
 * plan store. `categories` are raw values ("study", "work", "custom:生活").
 */
class AIAssistant(val store: AISettingsStore, val client: AIClient = AIClient()) {

    private suspend fun ask(instructions: String, input: String, timeout: Double = 40.0): String {
        val clean = input.trim()
        if (clean.isEmpty()) throw AIRequestError.emptyText
        if (AIDrafting.characterCount(clean) > AIDrafting.maximumInputCharacters) throw AIRequestError.tooMuchText
        val credentials = store.credentials()
        return client.complete(instructions, clean, credentials.entryID, credentials.configuration, credentials.apiKey, timeout)
    }

    /** The time, length, type and repeat written into one typed plan. */
    suspend fun readEntry(text: String, categories: List<String>, now: ZonedDateTime = ZonedDateTime.now()): EntrySuggestion {
        val answer = ask(AIDrafting.entryInstructions(now, categories), text, timeout = 20.0)
        return AIDrafting.entrySuggestion(answer, text, now, categories)
    }

    /**
     * 今日提要: the order of today's pending plans and why the first goes
     * first. Only titles, times, lengths, types and the nearest days are sent.
     */
    suspend fun dailyBrief(plans: List<CheckInItem>, events: List<CountdownEvent>, now: ZonedDateTime = ZonedDateTime.now()): AIDailyBrief.Answer {
        val request = AIDailyBrief.request(plans, events, now)
        if (request.plans.isEmpty()) throw AIRequestError.emptyText
        val answer = ask(AIDailyBrief.instructions(now), request.input, timeout = 25.0)
        return AIDailyBrief.parse(answer, request.plans)
    }

    /**
     * 晚间收尾: what to do with each plan left. Only what was done today (titles
     * and types), the plans left and the nearest days are sent.
     */
    suspend fun eveningWrap(done: List<CheckInItem>, pending: List<CheckInItem>, events: List<CountdownEvent>,
                            now: ZonedDateTime): Map<Int, WrapArrangement> {
        val plans = pending.take(AIEveningWrap.maximumPlans)
        if (plans.isEmpty()) throw AIRequestError.emptyText
        val answer = ask(AIEveningWrap.instructions(now), AIEveningWrap.input(done, plans, events, now), timeout = 25.0)
        return AIEveningWrap.arrangement(answer, plans.size)
    }

    /** 本周来信: the week's record, as for 周复盘. `week` is any day of the week. */
    suspend fun weeklyLetter(snapshot: PlanSnapshot, week: LocalDate, now: ZonedDateTime): AIWeeklyLetter.Draft {
        val record = AIDrafting.weekRecord(snapshot, week, now)
        if (record.plans.isEmpty()) throw AIRequestError.emptyText
        return AIWeeklyLetter.letter(ask(AIWeeklyLetter.instructions, record.text), record.plans)
    }

    suspend fun breakDown(item: CheckInItem, categories: List<String>, now: ZonedDateTime = ZonedDateTime.now()): List<PlanDraft> {
        // `categories` is unused, as on iOS: steps keep the plan's own type.
        val answer = ask(AIDrafting.breakdownInstructions, AIDrafting.breakdownInput(item))
        return AIDrafting.steps(answer, item, now)
    }

    suspend fun checklist(title: String, content: String): List<String> {
        val input = if (title.isEmpty()) content else "标题：$title\n$content"
        return AIDrafting.checklist(ask(AIDrafting.checklistInstructions, input))
    }

    suspend fun conversionHints(
        memoTitle: String,
        rows: List<String>,
        categories: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Map<Int, ConversionHint> {
        val answer = ask(AIDrafting.conversionInstructions(now, categories), AIDrafting.conversionInput(memoTitle, rows), timeout = 25.0)
        return AIDrafting.conversionHints(answer, rows.size, now, categories)
    }

    /** `week` is any day of the week; its Monday is used. */
    suspend fun draftReflection(snapshot: PlanSnapshot, week: LocalDate, now: ZonedDateTime = ZonedDateTime.now()): ReflectionDraft {
        val record = AIDrafting.weekRecord(snapshot, week, now)
        if (record.plans.isEmpty()) throw AIRequestError.emptyText
        return AIDrafting.reflection(ask(AIDrafting.reflectionInstructions, record.text), record.plans)
    }

    suspend fun draftNextWeek(
        snapshot: PlanSnapshot,
        week: LocalDate,
        categories: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
        fallbackCategory: String = defaultCategory,
    ): List<PlanDraft> {
        val answer = ask(AIDrafting.nextWeekInstructions(categories), AIDrafting.nextWeekInput(snapshot, week, now))
        return AIDrafting.nextWeekDrafts(answer, week, fallbackCategory, categories)
    }

    suspend fun draftPreparation(
        event: CountdownEvent,
        categories: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
        fallbackCategory: String = defaultCategory,
    ): List<PlanDraft> {
        val today = now.toLocalDate()
        // A repeating day is prepared for at its next occurrence, not its origin.
        val target = occurrence(event, now)
        val daysLeft = maxOf(0L, ChronoUnit.DAYS.between(today, target)).toInt()
        val answer = ask(AIDrafting.preparationInstructions(daysLeft, categories),
            AIDrafting.preparationInput(event.title, target, daysLeft))
        return AIDrafting.preparationDrafts(answer, target, now, fallbackCategory, categories)
    }

    suspend fun libraryFilter(sentence: String, categories: List<String>, now: ZonedDateTime = ZonedDateTime.now()): LibraryFilter {
        val answer = ask(AIDrafting.filterInstructions(now, categories), sentence, timeout = 20.0)
        return AIDrafting.libraryFilter(answer, now, categories)
    }

    suspend fun importPlans(
        text: String,
        categories: List<String>,
        now: ZonedDateTime = ZonedDateTime.now(),
        fallbackCategory: String = defaultCategory,
    ): List<PlanDraft> {
        val answer = ask(AIDrafting.importInstructions(now, categories), text)
        return AIDrafting.importDrafts(answer, now, fallbackCategory, categories)
    }

    companion object {
        val shared: AIAssistant get() = AIAssistant(AISettingsStore.shared)

        /** The built-in types and the user's own, from MojiViewModel.customCategories (names). */
        fun categories(customNames: List<String>): List<String> =
            (listOf("study", "work") + customNames.filter { it.isNotBlank() }.map { "custom:$it" }).distinct()

        @Volatile private var defaultCategoryReader: () -> String = { "study" }

        /** The type 计划默认值 names; a draft the model gave no known type falls back to it. */
        val defaultCategory: String get() = defaultCategoryReader().ifBlank { "study" }

        internal fun installDefaultCategory(context: Context) {
            val app = context.applicationContext as? MojiApplication ?: return
            defaultCategoryReader = { runCatching { app.settings.defaultCategory }.getOrDefault("study") }
        }

        /** The next time a countdown's day comes round, with the app's own repeat rules. */
        fun occurrence(event: CountdownEvent, now: ZonedDateTime): LocalDate {
            val today = now.toLocalDate()
            val origin = AIDrafting.instant(event.targetDate)?.atZone(now.zone)?.toLocalDate() ?: today
            val next = CountdownPolicy.nextOccurrenceDate(
                kotlinx.datetime.LocalDate(origin.year, origin.monthValue, origin.dayOfMonth),
                event.repeatRule ?: "never",
                kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth),
            )
            return LocalDate.parse(next.toString())
        }
    }
}

/** The sentence shown under a draft that could not be made. */
val Throwable.draftMessage: String
    get() = when (this) {
        is CancellationException -> "已取消。"
        is AIRequestError -> message
        else -> "没有完成，请稍后再试。"
    }
