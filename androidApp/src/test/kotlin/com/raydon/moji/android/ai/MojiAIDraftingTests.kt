package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.PlanWorkflowUndo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from MojiAIDraftingTests in MojiTests/MojiCoreTests.swift. The zone is
// fixed to Tokyo so every day below is the same on any machine.
class MojiAIDraftingTests {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    /** Thursday 2026-11-19, noon in Tokyo. */
    private val now: ZonedDateTime = Instant.parse("2026-11-19T03:00:00Z").atZone(zone)
    private val nowText = "2026-11-19T03:00:00Z"
    private val today: LocalDate get() = now.toLocalDate()
    private val categories = listOf("study", "work")

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private fun obj(json: String) = AIDrafting.`object`(json)

    private fun plan(
        title: String,
        category: String,
        start: String,
        minutes: Int = 25,
        note: String = "",
        scheduleKind: String? = "exactTime",
        detailsConfigured: Boolean? = true,
        plannedDurationEnabled: Boolean? = null,
    ) = CheckInItem(
        id = UUID.randomUUID().toString(),
        title = title,
        category = category,
        scheduledStart = start,
        plannedMinutes = minutes,
        note = note,
        createdAt = start,
        scheduleKind = scheduleKind,
        detailsConfigured = detailsConfigured,
        plannedDurationEnabled = plannedDurationEnabled,
    )

    @Test
    fun testDayIsComputedLocallyFromTheThreeForms() {
        fun dayOf(json: String) = AIDrafting.day(obj(json), now)
        assertEquals(day("2026-12-05"), dayOf("""{"date":"2026-12-05"}"""))
        assertEquals(day("2026-11-20"), dayOf("""{"offset_days":1}"""))
        assertEquals(day("2026-11-21"), dayOf("""{"weekday":6,"week_offset":0}"""))
        // “周三” said on a Thursday is the coming Wednesday, not yesterday.
        assertEquals(day("2026-11-25"), dayOf("""{"weekday":3,"week_offset":0}"""))
        assertEquals(day("2026-11-23"), dayOf("""{"weekday":1,"week_offset":1}"""))
        assertNull(dayOf("""{"date":"2026-02-30"}"""))
        assertNull(dayOf("""{"date":null,"offset_days":null,"weekday":9}"""))
        assertNull(dayOf("""{"offset_days":-3}"""))
        // 0 and 1 parsed from JSON are numbers, true and false are not.
        assertEquals(
            mapOf("a" to 0, "b" to 1, "c" to 1, "d" to 2, "e" to null, "f" to null, "g" to null, "h" to null),
            obj("""{"a":0,"b":1,"c":1.0,"d":" 2 ","e":true,"f":false,"g":null,"h":1e9}""").mapValues { AIDrafting.integer(it.value) },
        )
        assertEquals(day("2026-11-20"), dayOf("""{"offset_days":1}"""))
        assertNull(dayOf("""{"offset_days":true}"""))
    }

    @Test
    fun testEntrySuggestionReadsAFencedAnswerAndDropsWhatIsNotOffered() {
        val answer = "好的：\n```json\n{\"title\":\"交保险计划书\",\"offset_days\":1,\"part\":\"exact\",\"time\":\"15:00\",\"minutes\":45,\"category\":\"学习\",\"repeat\":\"weekly\",\"items\":[]}\n```"
        val suggestion = AIDrafting.entrySuggestion(answer, "明天下午三点交保险计划书 45分钟", now, categories)
        assertEquals("交保险计划书", suggestion.title)
        assertEquals(day("2026-11-20"), suggestion.day)
        assertEquals("exactTime", suggestion.scheduleKind)
        assertEquals(15, suggestion.hour); assertEquals(0, suggestion.minute)
        assertEquals(45, suggestion.minutes)
        assertEquals("study", suggestion.category)
        assertEquals("weekly", suggestion.repeatRule)
        assertTrue(suggestion.parts.isEmpty())
        assertEquals(listOf("明天", "15:00", AIDrafting.durationText(45), "学习", AIDrafting.repeatName("weekly")), suggestion.chips(today))
        assertEquals(listOf("明天", "15:00", "45 分钟", "学习", "每周"), suggestion.chips(today))

        val loose = AIDrafting.entrySuggestion(
            """{"title":"读书","offset_days":0,"part":"exact","time":"25:00","minutes":99999,"category":"钓鱼","repeat":"yearly","items":["读书"]}""",
            "读书", now, categories,
        )
        assertTrue("today, an impossible time, an unknown type and a single item leave nothing to suggest", loose.isEmpty)
        assertNull(loose.title)
        assertThrows(AIRequestError::class.java) { AIDrafting.entrySuggestion("没有 JSON", "读书", now, categories) }
    }

    @Test
    fun testEntrySuggestionSplitsSeveralThings() {
        val suggestion = AIDrafting.entrySuggestion(
            """{"title":null,"items":["买菜","取快递","买菜","给妈妈打电话"]}""",
            "买菜、取快递、给妈妈打电话", now, categories,
        )
        assertEquals(listOf("买菜", "取快递", "给妈妈打电话"), suggestion.parts)
        assertFalse(suggestion.hasDetails); assertFalse(suggestion.isEmpty)
    }

    @Test
    fun testAppliedSuggestionChangesOnlyRecognisedFields() {
        val original = plan(
            "明天下午三点交保险计划书 45分钟", "work", "2026-11-18T15:00:00Z",
            scheduleKind = "allDay", detailsConfigured = false, plannedDurationEnabled = false,
        ).copy(reminderMinutesBefore = 10)
        var suggestion = EntrySuggestion(title = "交保险计划书", day = day("2026-11-20"), scheduleKind = "exactTime", hour = 15, minute = 0, minutes = 45)
        val updated = suggestion.applied(original, zone)
        assertNotNull(updated); updated!!
        assertEquals(original.id, updated.id)
        assertEquals("交保险计划书", updated.title)
        assertEquals("2026-11-20T06:00:00Z", updated.scheduledStart)
        assertEquals("exactTime", updated.scheduleKind ?: "exactTime")
        assertEquals(45, AIDrafting.plannedDurationMinutes(updated))
        assertEquals("a type the sentence did not name stays", "work", updated.category)
        assertEquals(10, updated.reminderMinutesBefore)
        assertEquals("planned", updated.status)

        suggestion = EntrySuggestion(category = "study")
        val typed = suggestion.applied(original, zone)
        assertNotNull(typed); typed!!
        assertEquals("study", typed.category)
        assertEquals(original.scheduledStart, typed.scheduledStart)
        assertNull(AIDrafting.plannedDurationMinutes(typed))
        assertNull(EntrySuggestion().applied(original, zone))

        // A length on its own must not move a plan that already has a time.
        val timed = plan("开会", "work", "2026-11-19T00:30:00Z", plannedDurationEnabled = false)
        val longer = EntrySuggestion(minutes = 60).applied(timed, zone)
        assertNotNull(longer); longer!!
        assertEquals(timed.scheduledStart, longer.scheduledStart)
        assertEquals("exactTime", longer.scheduleKind ?: "exactTime")
        assertEquals(60, AIDrafting.plannedDurationMinutes(longer))
    }

    @Test
    fun testStepsOfACarriedOverPlanStartToday() {
        val carried = plan("写完研究计划书第三节", "study", "2026-11-16T01:00:00Z", minutes = 90)
        val drafts = AIDrafting.steps("""{"steps":[{"title":"列提纲","minutes":10},"查两篇文献",{"title":"  "},{"minutes":5}]}""", carried, now)
        assertEquals(listOf("列提纲", "查两篇文献"), drafts.map { it.title })
        assertEquals(listOf(10, null), drafts.map { it.minutes })
        assertTrue(drafts.all { it.day == day("2026-11-19") && it.category == "study" && it.scheduleKind == "allDay" })
        val saved = drafts[0].plan(note = "拆自：写完研究计划书第三节", zone = zone)
        assertEquals("拆自：写完研究计划书第三节", saved.note)
        assertEquals("allDay", saved.scheduleKind ?: "exactTime")
        assertEquals(10, AIDrafting.plannedDurationMinutes(saved))
        assertNull(AIDrafting.plannedDurationMinutes(drafts[1].plan(zone = zone)))
        assertThrows(AIRequestError::class.java) { AIDrafting.steps("""{"steps":[]}""", carried, now) }

        val future = plan("订机票", "work", "2026-11-25T01:00:00Z")
        assertEquals(day("2026-11-25"), AIDrafting.steps("""{"steps":["比价"]}""", future, now).first().day)
    }

    @Test
    fun testReflectionShowsOnlyEvidenceFoundInTheRecord() {
        val plans = listOf(plan("背单词", "study", nowText), plan("写周报", "work", nowText))
        val draft = AIDrafting.reflection(
            """{"note":"前半周很稳。","next_focus":"先拆第一步","evidence":[{"point":"按时完成","plans":[1,5]},{"point":"凭空的结论","plans":[9]},{"point":"没有依据"}]}""",
            plans,
        )
        assertEquals("前半周很稳。", draft.note); assertEquals("先拆第一步", draft.nextFocus)
        assertEquals(listOf(ReflectionDraft.Evidence(point = "按时完成", planTitles = listOf("背单词"))), draft.evidence)
        assertThrows(AIRequestError::class.java) { AIDrafting.reflection("""{"note":"","evidence":[]}""", plans) }
        assertTrue(AIDrafting.reflection("""{"note":"空的一周","evidence":[{"point":"x","plans":[1]}]}""", emptyList()).evidence.isEmpty())
    }

    @Test
    fun testWeekRecordNumbersPlansAndSendsNoNotes() {
        val done = plan("背单词", "study", "2026-11-17T01:00:00Z", note = "私人备注")
            .copy(status = "completed", completedAt = "2026-11-17T02:00:00Z")
        val open = plan("写周报", "work", "2026-11-18T01:00:00Z", minutes = 30)
        val other = plan("上上周的事", "work", "2026-11-03T01:00:00Z", minutes = 30)
        val snapshot = PlanSnapshot(checkInItems = listOf(done, open, other))
        val record = AIDrafting.weekRecord(snapshot, today, now)
        // Done first, then what is still open, oldest first; the numbers are what a point may cite.
        assertEquals(listOf("背单词", "上上周的事", "写周报"), record.plans.map { it.title })
        assertTrue(record.text.contains("[1] 背单词")); assertTrue(record.text.contains("[2] 上上周的事")); assertTrue(record.text.contains("[3] 写周报"))
        assertTrue(record.text.contains("完成 1 项，没做完 2 项，跳过 0 项"))
        assertTrue(record.text.contains("11月16日–11月22日"))
        assertFalse(record.text.contains("私人备注"))
    }

    @Test
    fun testNextWeekAndPreparationDraftsStayInsideTheirDays() {
        val next = AIDrafting.nextWeekDrafts(
            """{"plans":[{"title":"补第三节","weekday":2,"part":"afternoon","minutes":60,"category":"学习","reason":"本周没做完"},{"title":"没有星期","weekday":null},{"title":"复盘","weekday":7,"category":"钓鱼"}]}""",
            today, "work", categories, today = today,
        )
        assertEquals(listOf("补第三节", "复盘"), next.map { it.title })
        assertEquals(listOf(day("2026-11-24"), day("2026-11-29")), next.map { it.day })
        assertEquals("afternoon", next[0].scheduleKind); assertEquals("本周没做完", next[0].reason)
        assertEquals("an unknown type falls back, it is never invented", "work", next[1].category)

        val target = day("2026-11-29")
        val prep = AIDrafting.preparationDrafts(
            """{"plans":[{"title":"当天核对","days_before":0},{"title":"太早","days_before":30},{"title":"前三天","days_before":3},{"title":"负数","days_before":-2}]}""",
            target, now, "study", categories,
        )
        assertEquals(listOf("太早", "前三天", "当天核对"), prep.map { it.title })
        assertEquals(listOf(day("2026-11-19"), day("2026-11-26"), target), prep.map { it.day })
    }

    @Test
    fun testImportedPlansNeverLandInThePast() {
        val drafts = AIDrafting.importDrafts(
            """{"plans":[{"title":"交终稿","date":"2026-11-01","part":"exact","time":"17:00"},{"title":"预约体检","weekday":5,"week_offset":0,"part":"morning","minutes":20},{"title":"回邮件"}]}""",
            now, "study", categories,
        )
        assertEquals(listOf(day("2026-11-19"), day("2026-11-20"), day("2026-11-19")), drafts.map { it.day })
        assertEquals("exactTime", drafts[0].scheduleKind); assertEquals(17, drafts[0].hour)
        assertEquals("2026-11-19T08:00:00Z", drafts[0].plan(zone = zone).scheduledStart)
        assertEquals("morning", drafts[1].scheduleKind); assertEquals(20, drafts[1].minutes)
        assertEquals("allDay", drafts[2].scheduleKind)
    }

    @Test
    fun testChecklistAndConversionHints() {
        assertEquals(listOf("打印准考证", "带两支笔"), AIDrafting.checklist("""{"items":["打印准考证"," 打印准考证 ","",null,"带两支笔"]}"""))
        assertThrows(AIRequestError::class.java) { AIDrafting.checklist("""{"items":[]}""") }
        val hints = AIDrafting.conversionHints(
            """{"rows":[{"index":0,"offset_days":1,"category":"学习"},{"index":1,"category":null},{"index":7,"offset_days":2},{"index":2,"weekday":5,"week_offset":0}]}""",
            3, now, categories,
        )
        assertEquals(
            mapOf(0 to ConversionHint(day = day("2026-11-20"), category = "study"), 2 to ConversionHint(day = day("2026-11-20"), category = null)),
            hints,
        )
        assertEquals("明天 · 学习", hints[0]?.text(today))
    }

    @Test
    fun testLibraryFilterTurnsNamedPeriodsIntoRanges() {
        val filter = AIDrafting.libraryFilter("""{"keyword":null,"category":"学习","status":"planned","period":"last_week"}""", now, categories)
        assertEquals(
            LibraryFilter(keyword = "", category = "study", status = "planned", period = "range", from = day("2026-11-09"), to = day("2026-11-15")),
            filter,
        )
        val month = AIDrafting.libraryFilter("""{"keyword":"保险","period":"last_month","status":"weird"}""", now, categories)
        assertEquals("保险", month.keyword); assertEquals("all", month.status)
        assertEquals(day("2026-10-01"), month.from); assertEquals(day("2026-10-31"), month.to)
        val range = AIDrafting.libraryFilter("""{"period":"range","from":"2026-11-01","to":"2026-11-10"}""", now, categories)
        assertEquals(day("2026-11-01"), range.from); assertEquals(day("2026-11-10"), range.to)
        // 1.10.0 (A5): “全部” is a filter that limits nothing, not an answer that cannot be read.
        assertEquals(LibraryFilter(), AIDrafting.libraryFilter("""{"status":"all","period":"all"}""", now, categories))
        assertThrows(AIRequestError::class.java) { AIDrafting.libraryFilter("""{"status":"weird"}""", now, categories) }
        assertThrows(AIRequestError::class.java) { AIDrafting.libraryFilter("""{"keyword":null}""", now, categories) }
        assertThrows(AIRequestError::class.java) {
            AIDrafting.libraryFilter("""{"period":"range","from":"2026-11-10","to":"2026-11-01"}""", now, categories)
        }
    }

    @Test
    fun testOnlyTextWorthReadingIsSent() {
        assertFalse(AIDrafting.isWorthReading("买菜"))
        assertFalse(AIDrafting.isWorthReading("跑步"))
        assertTrue(AIDrafting.isWorthReading("明天下午三点交保险计划书"))
        assertTrue(AIDrafting.isWorthReading("买菜、取快递"))
        assertFalse(AIDrafting.isWorthReading("明天".repeat(150)))
        assertFalse(AIDrafting.looksLikeFilterSentence("保险"))
        assertTrue(AIDrafting.looksLikeFilterSentence("上周没做完的学习计划"))
        val item = plan("写第三节", "study", nowText, minutes = 90, note = "参考中出 2019", plannedDurationEnabled = true)
        assertEquals("计划：写第三节\n备注：参考中出 2019\n预计投入：90 分钟", AIDrafting.breakdownInput(item))
    }

    @Test
    fun testDraftUndoTakesBackOnlyWhatWasNotTouchedSince() {
        val original = plan("大计划", "study", nowText)
        val archived = original.copy(isArchived = true)
        val first = plan("第一步", "study", nowText)
        val second = plan("第二步", "study", nowText)
        val undo = PlanDraftUndo(added = listOf(first, second), changed = PlanWorkflowUndo(before = listOf(original), after = listOf(archived)))

        val state = PlanSnapshot(checkInItems = listOf(archived, first, second))
        val (untouched, all) = undo.apply(state)
        assertEquals(3, all)
        assertEquals(listOf(original), untouched.checkInItems)

        val edited = second.copy(title = "第二步（改过）")
        val (kept, partly) = undo.apply(state.copy(checkInItems = listOf(archived, first, edited)))
        assertEquals(2, partly)
        assertEquals("a step edited after it was accepted stays", listOf(original, edited), kept.checkInItems)

        val (blocked, count) = undo.apply(state, blockedId = first.id)
        assertEquals(2, count)
        assertEquals(listOf(original.id, first.id), blocked.checkInItems.map { it.id })
    }

    @Test
    fun testFixtureAnswersUsedForScreenshotsAreReadable() {
        val current = ZonedDateTime.now()
        val entry = AIQAFixture.answer(AIDrafting.entryInstructions(current, categories), "明天下午三点交保险计划书 45分钟")
        assertTrue(AIDrafting.entrySuggestion(entry, "明天下午三点交保险计划书 45分钟", current, categories).hasDetails)
        val split = AIQAFixture.answer(AIDrafting.entryInstructions(current, categories), "买菜、取快递、给妈妈打电话")
        assertEquals(3, AIDrafting.entrySuggestion(split, "买菜、取快递、给妈妈打电话", current, categories).parts.size)
        val item = plan("写完研究计划书第三节", "study", Instant.now().toString(), minutes = 90)
        assertEquals(4, AIDrafting.steps(AIQAFixture.answer(AIDrafting.breakdownInstructions, ""), item).size)
        assertEquals(4, AIDrafting.checklist(AIQAFixture.answer(AIDrafting.checklistInstructions, "")).size)
        assertEquals(2, AIDrafting.conversionHints(AIQAFixture.answer(AIDrafting.conversionInstructions(current, categories), ""), 4, current, categories).size)
        assertEquals(2, AIDrafting.reflection(AIQAFixture.answer(AIDrafting.reflectionInstructions, ""), listOf(item, item, item)).evidence.size)
        assertEquals(4, AIDrafting.nextWeekDrafts(AIQAFixture.answer(AIDrafting.nextWeekInstructions(categories), ""), current.toLocalDate(), "study", categories).size)
        assertEquals(4, AIDrafting.preparationDrafts(AIQAFixture.answer(AIDrafting.preparationInstructions(20, categories), ""),
            current.toLocalDate().plusDays(20), current, "study", categories).size)
        assertEquals("planned", AIDrafting.libraryFilter(AIQAFixture.answer(AIDrafting.filterInstructions(current, categories), ""), current, categories).status)
        assertEquals(3, AIDrafting.importDrafts(AIQAFixture.answer(AIDrafting.importInstructions(current, categories), ""), current, "study", categories).size)
    }
}
