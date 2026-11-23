package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// AI batches 2 and 3 (11-23): ported from MojiEveningWrapTests,
// MojiWeeklyLetterTests, MojiAIWriteLogTests and MojiShareInboxTests in
// MojiTests/MojiCoreTests.swift, with the same samples, so 收尾, 本周来信 and
// 留痕 say the same words on both platforms. The zone is fixed to Tokyo and a
// week starts on Monday; Sunday 2026-11-22 closes the week of the 16th.

private val tokyoZone: ZoneId = ZoneId.of("Asia/Tokyo")

/** [day] [hour]:[minute] of November 2026 (or of [month]) in Tokyo. */
private fun tokyo(day: Int, hour: Int, minute: Int = 0, month: Int = 11): ZonedDateTime =
    ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, tokyoZone)

private fun iso(time: ZonedDateTime): String = time.toInstant().toString()

private fun dayOf(text: String): LocalDate = Instant.parse(text).atZone(tokyoZone).toLocalDate()

private fun samplePlan(
    title: String,
    start: ZonedDateTime,
    minutes: Int = 25,
    category: String = "study",
    status: String = "planned",
    durationEnabled: Boolean? = null,
) = CheckInItem(
    id = UUID.randomUUID().toString(),
    title = title,
    category = category,
    scheduledStart = iso(start),
    plannedMinutes = minutes,
    status = status,
    completedAt = if (status == "completed") iso(start.plusHours(1)) else null,
    createdAt = iso(start.minusDays(1)),
    plannedDurationEnabled = durationEnabled,
)

private fun sampleRecord(title: String, from: ZonedDateTime, to: ZonedDateTime, category: String = "study") =
    TimeRecord(id = UUID.randomUUID().toString(), title = title, category = category, startDate = iso(from), endDate = iso(to))

/** ④ 晚间收尾: the sentences, the reading of the answer and what 照此处理 writes. */
class MojiEveningWrapTests {
    @Test
    fun testTheFirstSentenceComparesOnlyARealDifference() {
        assertEquals("今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。",
            AIEveningWrap.factsLine(EveningWrapFacts(completed = 3, focusMinutes = 100, weekAverage = 80)))
        assertEquals("今天还没有完成的计划。",
            AIEveningWrap.factsLine(EveningWrapFacts(completed = 0, focusMinutes = 0, weekAverage = 80)))
        assertEquals("a Monday has nothing to compare with", "今天完成 2 件，专注 30 分钟。",
            AIEveningWrap.factsLine(EveningWrapFacts(completed = 2, focusMinutes = 30, weekAverage = null)))
        assertEquals("five minutes is not a difference", "今天完成 2 件，专注 1 小时 25 分钟。",
            AIEveningWrap.factsLine(EveningWrapFacts(completed = 2, focusMinutes = 85, weekAverage = 80)))
        assertEquals("今天完成 1 件，专注 50 分钟，比这周平均少 30 分钟。",
            AIEveningWrap.factsLine(EveningWrapFacts(completed = 1, focusMinutes = 50, weekAverage = 80)))
    }

    @Test
    fun testTheSecondSentenceSaysWhereTheRestStands() {
        assertEquals("剩下的已经安排好了。", AIEveningWrap.secondLine(applied = true, pending = 2, hasArrangement = true))
        assertEquals("今天的计划都做完了。", AIEveningWrap.secondLine(applied = false, pending = 0, hasArrangement = false))
        assertEquals("剩下 2 件这样安排：", AIEveningWrap.secondLine(applied = false, pending = 2, hasArrangement = true))
        assertEquals("剩下 2 件没做完。", AIEveningWrap.secondLine(applied = false, pending = 2, hasArrangement = false))
        assertTrue(AIEveningWrap.shouldShow(EveningWrapFacts(completed = 1), pending = 0))
        assertTrue(AIEveningWrap.shouldShow(EveningWrapFacts(), pending = 1))
        assertFalse(AIEveningWrap.shouldShow(EveningWrapFacts(), pending = 0))
    }

    @Test
    fun testTheFactsAreTheReviewPagesNumbers() {
        // Monday 16 and Tuesday 17 average 80 minutes; Wednesday 18 is today.
        val records = listOf(
            sampleRecord("一", tokyo(16, 9), tokyo(16, 10)),
            sampleRecord("二", tokyo(17, 9), tokyo(17, 10, 40)),
            sampleRecord("三", tokyo(18, 9), tokyo(18, 10, 40), "work"),
        )
        val done = (0 until 3).map { index -> samplePlan("做完 $index", tokyo(18, 8 + index), 30, "work", "completed") }
        val pending = samplePlan("没做完", tokyo(18, 15), 30, "work")
        val facts = AIEveningWrap.facts(done + pending, records, LocalDate.of(2026, 11, 18), tokyoZone)
        assertEquals(EveningWrapFacts(completed = 3, focusMinutes = 100, weekAverage = 80), facts)
        assertEquals("今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。", AIEveningWrap.factsLine(facts))
        val monday = AIEveningWrap.facts(emptyList(), records, LocalDate.of(2026, 11, 16), tokyoZone)
        assertNull(monday.weekAverage)
        assertEquals(60, monday.focusMinutes)
    }

    @Test
    fun testTheAnswerIsReadPlanByPlan() {
        val answer = """{"items":[{"index":2,"action":"drop","note":"已经不需要了。"},{"index":1,"action":"split"}]}"""
        val result = AIEveningWrap.arrangement(answer, 3)
        assertEquals(WrapArrangement(WrapAction.Split, null), result[1])
        assertEquals(WrapArrangement(WrapAction.Drop, "已经不需要了"), result[2])
        assertNull(result[3])
        assertEquals("a plan left out is carried to tomorrow", WrapAction.Tomorrow, result[3]?.action ?: WrapAction.Tomorrow)

        assertThrows(AIRequestError::class.java) {
            AIEveningWrap.arrangement("""{"items":[{"index":1,"action":"later"},{"index":true,"action":"drop"}]}""", 3)
        }
        assertThrows(AIRequestError::class.java) { AIEveningWrap.arrangement("""{"items":[{"index":4,"action":"drop"}]}""", 3) }
        assertThrows(AIRequestError::class.java) { AIEveningWrap.arrangement("""{"items":[{"index":1,"action":"keep"}]}""", 1) }
        assertThrows(AIRequestError::class.java) { AIEveningWrap.arrangement("不是 JSON", 2) }

        val repeated = AIEveningWrap.arrangement(
            """{"items":[{"index":1,"action":"morning"},{"index":1,"action":"drop"},{"index":2,"action":"tomorrow","note":"离交付还有两天。"}]}""",
            2,
        )
        assertEquals("only the first answer for a number counts", WrapAction.Morning, repeated[1]?.action)
        assertEquals(WrapArrangement(WrapAction.Tomorrow, "离交付还有两天"), repeated[2])
    }

    @Test
    fun testCarryingOutTheArrangement() {
        val today = LocalDate.of(2026, 11, 18)
        val tomorrow = today.plusDays(1)
        val report = samplePlan("交周报", tokyo(18, 14, 30), 45, "work", durationEnabled = true)
        val reading = samplePlan("阅读产品设计章节", tokyo(18, 16), 60, "study", durationEnabled = true)
        val big = samplePlan("归纳上周待办", tokyo(18, 19), 30, "work", durationEnabled = true)
        val old = samplePlan("旧清单", tokyo(18, 10), 20)
        val stays = samplePlan("留着", tokyo(18, 20), 20)
        val running = samplePlan("正在做", tokyo(18, 21), 25, "work", status = "inProgress")

        val outcome = AIEveningWrap.apply(
            listOf(report to WrapAction.Tomorrow, reading to WrapAction.Morning, big to WrapAction.Split,
                old to WrapAction.Drop, stays to WrapAction.Keep, running to WrapAction.Tomorrow),
            today, tokyoZone,
        )
        assertEquals("收尾：顺延 1 件，明早 2 件，放下 1 件", outcome.summary)
        assertEquals("kept and in-progress plans are not written", 4, outcome.changed.size)
        assertEquals(big.id, outcome.firstSplit)

        val moved = outcome.changed.first { it.id == report.id }
        assertEquals("tomorrow keeps the time", iso(tokyo(19, 14, 30)), moved.scheduledStart)
        val morning = outcome.changed.first { it.id == reading.id }
        assertEquals(tomorrow, dayOf(morning.scheduledStart))
        assertEquals("morning", morning.scheduleKind)
        assertEquals("the length stays", 60, AIDrafting.plannedDurationMinutes(morning))
        val split = outcome.changed.first { it.id == big.id }
        assertEquals("morning", split.scheduleKind)
        assertEquals(tomorrow, dayOf(split.scheduledStart))
        assertEquals(30, AIDrafting.plannedDurationMinutes(split))
        val dropped = outcome.changed.first { it.id == old.id }
        assertEquals(true, dropped.isArchived)
        assertEquals(old.scheduledStart, dropped.scheduledStart)
        assertNull(outcome.changed.firstOrNull { it.id == stays.id })
        assertNull(outcome.changed.firstOrNull { it.id == running.id })
    }

    @Test
    fun testTheEveningStartsAtNine() {
        assertFalse(AIEveningWrap.isEvening(tokyo(23, 20, 59)))
        assertTrue(AIEveningWrap.isEvening(tokyo(23, 21, 0)))
        assertTrue(AIEveningWrap.isEvening(tokyo(23, 23, 59)))
    }

    @Test
    fun testTheKeysAreTheSharedOnes() {
        assertEquals("moji.ai.wrap.v1", AIEveningWrapState.wrapKey)
        assertEquals("moji.ai.wrap.attempt", AIEveningWrapState.attemptKey)
        assertEquals("moji.ai.wrap.dismissedDay", AIEveningWrapState.dismissedDayKey)
        assertEquals("moji.ai.wrap.appliedDay", AIEveningWrapState.appliedDayKey)
        assertEquals(listOf("tomorrow", "morning", "split", "drop", "keep"), WrapAction.entries.map { it.raw })
    }

    @Test
    fun testTheEveningsAnswerIsKeptForItsDayOnly() {
        val prefs = AIMemoryPreferences()
        val today = LocalDate.of(2026, 11, 23)
        val tomorrow = today.plusDays(1)
        val plan = samplePlan("A", tokyo(23, 15))
        prefs.putString(AIEveningWrapState.wrapKey,
            """{"day":"2026-11-23","items":[{"id":"${plan.id}","action":"drop","note":"不必再做"},{"id":"${plan.id}","action":"morning"},{"id":"别的","action":"later"}]}""")
        val state = AIEveningWrapState(prefs)
        assertEquals(WrapAction.Drop, state.arrangement(today)[plan.id]?.action)
        assertEquals("the first entry for a plan wins", "不必再做", state.arrangement(today)[plan.id]?.note)
        assertEquals("an unknown action is passed over", 1, state.arrangement(today).size)
        assertTrue(state.arrangement(tomorrow).isEmpty())
        assertTrue(state.hasArrangement(today, listOf(plan)))
        assertFalse(state.hasArrangement(today, emptyList()))
        assertFalse(state.hasArrangement(today, listOf(samplePlan("B", tokyo(23, 16)))))

        state.dismiss(today)
        assertTrue(state.isDismissed(today))
        assertFalse(state.isDismissed(tomorrow))
        state.markApplied(today)
        assertTrue("kept across launches", AIEveningWrapState(prefs).isApplied(today))
        assertTrue(AIEveningWrapState(prefs).isDismissed(today))
        assertFalse(AIEveningWrapState(prefs).isApplied(tomorrow))

        val other = AIMemoryPreferences()
        AIEveningWrapState(other).save(AIEveningWrapState.Saved("2026-11-23", listOf(
            AIEveningWrapState.Entry(plan.id, WrapAction.Split, null),
            AIEveningWrapState.Entry("另一件", WrapAction.Tomorrow, "离交付还有两天"),
        )))
        val reread = AIEveningWrapState(other).arrangement(today)
        assertEquals(AIEveningWrapState.Entry(plan.id, WrapAction.Split, null), reread[plan.id])
        assertEquals("离交付还有两天", reread["另一件"]?.note)
        assertFalse("no note is written when there is none",
            Json.parseToJsonElement(other.getString(AIEveningWrapState.wrapKey) ?: "{}").jsonObject["items"]!!
                .jsonArray[0].jsonObject.containsKey("note"))

        // Nothing is asked while the model is not ready.
        val fresh = AIEveningWrapState(AIMemoryPreferences())
        val ai = AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore())
        fresh.refreshIfNeeded(emptyList(), listOf(plan), emptyList(), tokyo(23, 21, 30), ai)
        assertFalse(fresh.loading.value)
        assertNull(fresh.lastAttempt)
    }

    @Test
    fun testTheQAFixtureCarriesTheLatePlanSplit() {
        val now = tokyo(23, 21, 30)
        val today = samplePlan("整理 1.6.0 发布说明", tokyo(23, 15), 45, "work", durationEnabled = true)
        val late = samplePlan("归纳昨日待办", tokyo(22, 14), 30, "work")
        val done = samplePlan("整理会议纪要", tokyo(23, 10), status = "completed")
        val input = AIEveningWrap.input(listOf(done), listOf(today, late), emptyList(), now)
        assertTrue(input.startsWith("今天做完的：\n- 整理会议纪要（学习）\n没做完的：\n[1] 整理 1.6.0 发布说明"))
        assertTrue(input.contains("[2] 归纳昨日待办（") && input.contains("已顺延 1 天"))
        val result = AIEveningWrap.arrangement(AIQAFixture.answer(AIEveningWrap.instructions(now), input), 2)
        assertEquals(WrapArrangement(WrapAction.Tomorrow, null), result[1])
        assertEquals(WrapArrangement(WrapAction.Split, "已经顺延三天"), result[2])
    }
}

/** ⑥ 本周来信: which week it is about, the sentence of facts and the reading of the answer. */
class MojiWeeklyLetterTests {
    @Test
    fun testTheWeekTurnsOnSundayEvening() {
        // Sunday 22 November.
        assertEquals(LocalDate.of(2026, 11, 9), AIWeeklyLetter.letterWeek(tokyo(22, 19, 59)))
        assertEquals(LocalDate.of(2026, 11, 16), AIWeeklyLetter.letterWeek(tokyo(22, 20, 0)))
        assertEquals("on Monday morning it is the week just past", LocalDate.of(2026, 11, 16), AIWeeklyLetter.letterWeek(tokyo(23, 9, 0)))
        assertEquals(tokyo(22, 20), AIWeeklyLetter.nextNotification(tokyo(18, 10)))
        assertEquals(tokyo(29, 20), AIWeeklyLetter.nextNotification(tokyo(22, 20)))
        assertEquals(tokyo(22, 20), AIWeeklyLetter.nextNotification(tokyo(22, 19, 59)))
    }

    @Test
    fun testWhenTheTeaserAndThePreparationHappen() {
        assertFalse(AIWeeklyLetter.isTeaserTime(tokyo(22, 19, 59)))
        assertTrue(AIWeeklyLetter.isTeaserTime(tokyo(22, 20, 0)))
        assertTrue(AIWeeklyLetter.isTeaserTime(tokyo(23, 23, 59)))
        assertFalse(AIWeeklyLetter.isTeaserTime(tokyo(24, 0, 0)))
        assertFalse(AIWeeklyLetter.isPreparingTime(tokyo(22, 11, 59)))
        assertTrue(AIWeeklyLetter.isPreparingTime(tokyo(22, 12, 0)))
        assertFalse(AIWeeklyLetter.isPreparingTime(tokyo(22, 20, 0)))
        assertFalse(AIWeeklyLetter.isPreparingTime(tokyo(21, 15, 0)))
    }

    @Test
    fun testTheRangeAndTheKey() {
        assertEquals("11月16日 – 22日", AIWeeklyLetter.rangeText(LocalDate.of(2026, 11, 16)))
        assertEquals("11月30日 – 12月6日", AIWeeklyLetter.rangeText(LocalDate.of(2026, 11, 30)))
        val key = AIWeeklyLetter.weekKey(LocalDate.of(2026, 11, 16))
        assertEquals("2026-11-16", key)
        assertEquals("any day keys its week", key, AIWeeklyLetter.weekKey(LocalDate.of(2026, 11, 22)))
        assertEquals(LocalDate.of(2026, 11, 16), AIWeeklyLetter.monday(key))
        assertNull(AIWeeklyLetter.monday("11-16"))
    }

    @Test
    fun testTheFactsSentence() {
        assertEquals("这周完成 9/14，专注 6 小时 20 分钟，比上周多 1 小时。",
            AIWeeklyLetter.factsLine(AIWeeklyLetter.Facts(planned = 14, completed = 9, focusMinutes = 380, previousFocusMinutes = 320)))
        assertEquals("nothing last week, nothing to compare", "这周完成 9/14，专注 6 小时 20 分钟。",
            AIWeeklyLetter.factsLine(AIWeeklyLetter.Facts(planned = 14, completed = 9, focusMinutes = 380, previousFocusMinutes = 0)))
        assertEquals("这周完成 2/5。",
            AIWeeklyLetter.factsLine(AIWeeklyLetter.Facts(planned = 5, completed = 2, focusMinutes = 0, previousFocusMinutes = 90)))
        assertEquals("这周完成 6/6，专注 3 小时 20 分钟，比上周少 1 小时。",
            AIWeeklyLetter.factsLine(AIWeeklyLetter.Facts(planned = 6, completed = 6, focusMinutes = 200, previousFocusMinutes = 260)))
    }

    @Test
    fun testTheFactsAreTheReviewPagesWeek() {
        val items = listOf(
            samplePlan("周一做完", tokyo(16, 9), status = "completed"),
            samplePlan("周四没做", tokyo(19, 15)),
            samplePlan("周日做完", tokyo(22, 10), status = "completed"),
            samplePlan("下周的", tokyo(23, 10)),
            samplePlan("上周的", tokyo(15, 10), status = "completed"),
        )
        val records = listOf(sampleRecord("本周", tokyo(17, 9), tokyo(17, 11)), sampleRecord("上周", tokyo(10, 9), tokyo(10, 10)))
        val facts = AIWeeklyLetter.facts(items, records, LocalDate.of(2026, 11, 16), tokyoZone)
        assertEquals(AIWeeklyLetter.Facts(planned = 3, completed = 2, focusMinutes = 120, previousFocusMinutes = 60), facts)
        assertEquals("这周完成 2/3，专注 2 小时，比上周多 1 小时。", AIWeeklyLetter.factsLine(facts))
        assertEquals(3, AIWeeklyLetter.minimumPlans)
    }

    @Test
    fun testTheLetterIsReadFromTheAnswer() {
        val plans = listOf(samplePlan("归纳上周待办", tokyo(18, 9), 30, "work"), samplePlan("准备季度汇报", tokyo(19, 9), 60, "work"))
        val answer = """{"title":"「卡在第一步的一周」","body":"前半周很稳；周四起两件大事一直顺延。","next_focus":"先把大事拆出第一步。","evidence":[{"point":"「归纳上周待办」顺延 4 次","plans":[1]},{"point":"编号超出","plans":[5]},{"point":"还没开始","plans":[2,9]}]}"""
        val draft = AIWeeklyLetter.letter(answer, plans)
        assertEquals("卡在第一步的一周", draft.title)
        assertEquals("前半周很稳；周四起两件大事一直顺延。", draft.body)
        assertEquals("先把大事拆出第一步。", draft.nextFocus)
        assertEquals(listOf(WeeklyLetter.Evidence("「归纳上周待办」顺延 4 次", listOf("归纳上周待办")),
            WeeklyLetter.Evidence("还没开始", listOf("准备季度汇报"))), draft.evidence)
        assertEquals("依据：「归纳上周待办」顺延 4 次 · 还没开始", AIWeeklyLetter.evidenceLine(draft.evidence))
        assertNull(AIWeeklyLetter.evidenceLine(emptyList()))

        assertThrows(AIRequestError::class.java) { AIWeeklyLetter.letter("""{"title":"","body":"有正文"}""", plans) }
        assertThrows(AIRequestError::class.java) { AIWeeklyLetter.letter("""{"title":"「」","body":"有正文"}""", plans) }
        assertThrows(AIRequestError::class.java) { AIWeeklyLetter.letter("""{"title":"一周"}""", plans) }
    }

    @Test
    fun testTheLetterGoesIntoTheReflectionAsADraft() {
        val letter = WeeklyLetter("2026-11-16", "卡在第一步的一周", "前半周很稳。", "先拆第一步。", "这周完成 9/14。",
            listOf(WeeklyLetter.Evidence("一直顺延", listOf("归纳上周待办"))), 0.0)
        val draft = AIWeeklyLetter.reflectionDraft(letter)
        assertEquals("这周完成 9/14。前半周很稳。", draft.note)
        assertEquals("先拆第一步。", draft.nextFocus)
        assertEquals(listOf(listOf("归纳上周待办")), draft.evidence.map { it.planTitles })
        assertEquals("这周的来信：「卡在第一步的一周」", AIWeeklyLetter.notificationBody("卡在第一步的一周"))
        assertEquals("这一周过完了，打开回顾看看墨记的来信。", AIWeeklyLetter.notificationBody(null))
        assertEquals("moji.ai.weeklyLetter", AIWeeklyLetter.notificationIdentifier)
    }

    @Test
    fun testOneLetterAWeekAndTheNewestFiftyTwo() {
        val prefs = AIMemoryPreferences()
        fun letter(week: String, title: String) = WeeklyLetter(week, title, "正文", "", "", emptyList(), 0.0)
        val state = AIWeeklyLetterState(prefs)
        state.store(letter("2026-11-09", "旧"))
        state.store(letter("2026-11-16", "一"))
        state.store(letter("2026-11-16", "二"))
        assertEquals("one letter a week, the newer one kept", listOf("旧", "二"), state.letters.value.map { it.title })
        assertEquals(listOf("2026-11-16", "2026-11-09"), state.newestFirst.map { it.week })
        assertEquals("kept across launches", 2, AIWeeklyLetterState(prefs).letters.value.size)
        assertEquals("二", state.letter(LocalDate.of(2026, 11, 19))?.title)

        var many = emptyList<WeeklyLetter>()
        val start = LocalDate.of(2025, 1, 6)
        for (index in 0 until 55) {
            many = AIWeeklyLetterState.merged(many, letter(AIWeeklyLetter.weekKey(start.plusDays(7L * index)), "$index"))
        }
        assertEquals(AIWeeklyLetter.limit, many.size)
        assertEquals("the oldest go first", "3", many.first().title)

        state.dismiss(LocalDate.of(2026, 11, 16))
        assertTrue(state.isDismissed(LocalDate.of(2026, 11, 16)))
        assertFalse(state.isDismissed(LocalDate.of(2026, 11, 9)))
        assertTrue("kept across launches", AIWeeklyLetterState(prefs).isDismissed(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun testTheKeptLettersReadBackTheSame() {
        assertEquals("moji.ai.letters.v1", AIWeeklyLetterState.lettersKey)
        assertEquals("moji.ai.letter.attempt", AIWeeklyLetterState.attemptKey)
        assertEquals("moji.ai.letter.dismissedWeek", AIWeeklyLetterState.dismissedWeekKey)
        val letter = WeeklyLetter("2026-11-16", "卡在第一步的一周", "前半周很稳。", "先拆第一步。", "这周完成 9/14。",
            listOf(WeeklyLetter.Evidence("一直顺延", listOf("归纳上周待办", "准备季度汇报"))), 1_763_809_200.5)
        val text = AIWeeklyLetterState.encode(listOf(letter))
        assertEquals(listOf(letter), AIWeeklyLetterState.decode(text))
        assertEquals(setOf("week", "title", "body", "nextFocus", "facts", "evidence", "createdAt"),
            Json.parseToJsonElement(text).jsonArray[0].jsonObject.keys)
        assertTrue(AIWeeklyLetterState.decode("{not json").isEmpty())
        assertTrue(AIWeeklyLetterState.decode(null).isEmpty())
    }

    @Test
    fun testALetterIsAskedForWithThreePlansOnceAWeek() {
        val ai = AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore())
        val state = AIWeeklyLetterState(AIMemoryPreferences())
        val monday = LocalDate.of(2026, 11, 16)
        val now = tokyo(22, 20, 30)
        val two = PlanSnapshot(checkInItems = listOf(samplePlan("一", tokyo(16, 9)), samplePlan("二", tokyo(17, 9))))
        val three = two.copy(checkInItems = two.checkInItems + samplePlan("三", tokyo(18, 9)))
        assertFalse("not while the model is not ready", state.canAsk(monday, three, now, ai))
        state.refreshIfNeeded(monday, three, now, ai)
        assertNull(state.loadingWeek.value)
        assertNull(state.lastAttempt)

        ai.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "sk-test-123")
        ai.change { it.copy(isEnabled = true) }
        assertTrue(state.canAsk(monday, three, now, ai))
        assertFalse("fewer than three plans that week", state.canAsk(monday, two, now, ai))
        ai.change { it.copy(weeklyLetter = false) }
        assertFalse("switched off", state.canAsk(monday, three, now, ai))
        ai.change { it.copy(weeklyLetter = true) }
        state.lastAttempt = now.toEpochSecond() - 60
        assertFalse("a failed try waits half an hour", state.canAsk(monday, three, now, ai))
        state.lastAttempt = now.toEpochSecond() - 31 * 60
        assertTrue(state.canAsk(monday, three, now, ai))
        state.store(WeeklyLetter("2026-11-16", "一周", "正文", "", "", emptyList(), 0.0))
        assertFalse("one letter a week", state.canAsk(monday, three, now, ai))
    }

    @Test
    fun testTheQAFixtureWritesALetterOnTheWeeksRecord() {
        val now = tokyo(22, 20, 30)
        val snapshot = PlanSnapshot(checkInItems = listOf(
            samplePlan("整理 1.6.0 发布说明", tokyo(17, 10), 45, "work", "completed"),
            samplePlan("归纳昨日待办", tokyo(18, 14), 30, "work"),
            samplePlan("阅读产品设计章节", tokyo(20, 9), 60, "study"),
        ))
        val record = AIDrafting.weekRecord(snapshot, LocalDate.of(2026, 11, 16), now)
        val draft = AIWeeklyLetter.letter(AIQAFixture.answer(AIWeeklyLetter.instructions, record.text), record.plans)
        assertEquals("卡在第一步的一周", draft.title)
        assertEquals(listOf(listOf("归纳昨日待办"), listOf("整理 1.6.0 发布说明")), draft.evidence.map { it.plans })

        val archive = AIWeeklyLetterState(AIMemoryPreferences())
        archive.seedArchiveForQA(now)
        assertEquals(listOf("2026-11-09", "2026-11-02", "2026-10-26"), archive.newestFirst.map { it.week })
        assertEquals("the letter ai-letter-detail opens", "顺延集中在周四", archive.newestFirst.first().title)
    }

    @Test
    fun testTheLetterScreensMoveTheSampleIntoTheWeek() {
        val plan = samplePlan("周一做完", tokyo(23, 9), status = "completed")
        val record = sampleRecord("专注", tokyo(23, 9), tokyo(23, 10))
        val snapshot = PlanSnapshot(records = listOf(record), checkInItems = listOf(plan))
        val moved = AIQAFixture.shifted(snapshot, 6, tokyoZone)
        assertEquals(iso(tokyo(29, 9)), moved.checkInItems[0].scheduledStart)
        assertEquals(iso(tokyo(29, 10)), moved.checkInItems[0].completedAt)
        assertEquals(iso(tokyo(29, 9)), moved.records[0].startDate)
        assertEquals(iso(tokyo(29, 10)), moved.records[0].endDate)
        assertEquals(snapshot, AIQAFixture.shifted(snapshot, 0, tokyoZone))
    }
}

/** ⑧ 留痕: what each write says, the log that keeps it and the undo that leaves edited plans alone. */
class MojiAIWriteLogTests {
    @Test
    fun testWhereAPlanCameFrom() {
        assertEquals("拆自「准备季度汇报」", AIWriteText.splitOrigin("准备季度汇报"))
        assertEquals("墨记拆自「准备季度汇报」", AIWriteText.mark(AIWriteText.splitOrigin("准备季度汇报")))
        assertEquals("twelve characters stay whole", "拆自「整理第三季度客户回访记录」", AIWriteText.splitOrigin("整理第三季度客户回访记录"))
        assertEquals("拆自「整理第三季度客户回访记…」", AIWriteText.splitOrigin("整理第三季度客户回访记录表"))
        assertEquals("为「项目交付」拟", AIWriteText.preparationOrigin("项目交付"))
        assertEquals("墨记按上周记录拟", AIWriteText.mark(AIWriteText.nextWeekOrigin))
        assertEquals("从文字整理", AIWriteText.textOrigin)
        assertEquals("从截图整理", AIWriteText.imageOrigin)
        assertEquals("从分享整理", AIWriteText.shareOrigin)
    }

    @Test
    fun testWhatEachWriteSays() {
        assertEquals("拆成 3 步：「准备季度汇报」，原计划已归档", AIWriteText.breakdown(3, "准备季度汇报", archived = true))
        assertEquals("拆成 2 步：「准备季度汇报」", AIWriteText.breakdown(2, "准备季度汇报", archived = false))
        assertEquals("拆成 2 条：「买菜和取快递」", AIWriteText.entrySplit(2, "买菜和取快递"))
        assertEquals("改了「交周报」的日期、时间", AIWriteText.entryChange("交周报", listOf("日期", "时间")))
        assertEquals("从分享加入 3 条", AIWriteText.imported(3, AIWriteText.shareOrigin))
        assertEquals("从截图加入 2 条", AIWriteText.imported(2, AIWriteText.imageOrigin))
        assertEquals("从文字加入 1 条", AIWriteText.imported(1, AIWriteText.textOrigin))
        assertEquals("拟下周 4 条", AIWriteText.nextWeek(4))
        assertEquals("为「项目交付」拟 3 条", AIWriteText.preparation(3, "项目交付"))
        assertEquals("收尾：顺延 1 件，明早 2 件，放下 1 件", AIWriteText.wrap(tomorrow = 1, morning = 2, dropped = 1))
        assertEquals("收尾：放下 2 件", AIWriteText.wrap(tomorrow = 0, morning = 0, dropped = 2))
        assertEquals("已撤销", AIWriteText.undoResult(undone = 3, total = 3))
        assertEquals("已撤销 2 条；之后改过的保持不变", AIWriteText.undoResult(undone = 2, total = 3))
        assertEquals("这些计划之后都改过，保持不变", AIWriteText.undoResult(undone = 0, total = 3))
    }

    @Test
    fun testTheChangedFieldsAreNamedInOrder() {
        val start = tokyo(23, 15)
        val plan = CheckInItem(id = UUID.randomUUID().toString(), title = "交周报", category = "work", scheduledStart = iso(start),
            plannedMinutes = 45, createdAt = iso(start), scheduleKind = "exactTime", plannedDurationEnabled = true)
        val moved = plan.copy(scheduledStart = iso(start.plusHours(18)), plannedMinutes = 30, title = "交月报")
        assertEquals(listOf("日期", "时间", "时长", "标题"), AIWriteText.changedFields(plan, moved, tokyoZone))
        assertEquals(emptyList<String>(), AIWriteText.changedFields(plan, plan, tokyoZone))
        val recurring = plan.copy(repeatRule = "weekly", category = "study")
        assertEquals(listOf("类型", "重复"), AIWriteText.changedFields(plan, recurring, tokyoZone))
        val morning = plan.copy(scheduleKind = "morning", scheduledStart = iso(tokyo(23, 9)))
        assertEquals(listOf("时间"), AIWriteText.changedFields(plan, morning, tokyoZone))
    }

    @Test
    fun testTheLogKeepsAWeekAndAtMostTwoHundred() {
        assertEquals("moji.ai.writes.v1", AIWriteLog.key)
        val prefs = AIMemoryPreferences()
        val now = tokyo(23, 15).toInstant()
        val today = LocalDate.of(2026, 11, 23)
        val log = AIWriteLog(prefs, now, tokyoZone)
        val first = log.record("一", emptyList(), emptyList(), emptyList(), now)
        log.record("二", emptyList(), emptyList(), emptyList(), now.plusSeconds(1))
        log.record("三", emptyList(), emptyList(), emptyList(), now.plusSeconds(2))
        log.markUndone(first)
        assertEquals(2, log.activeCount(today))
        assertEquals("newest first", listOf("三", "二", "一"), log.entries(today).map { it.summary })
        assertEquals("kept across launches", 2, AIWriteLog(prefs, now, tokyoZone).activeCount(today))
        assertEquals(true, log.entry(first)?.undone)
        assertEquals(0, log.activeCount(today.plusDays(1)))

        fun entry(summary: String, daysAgo: Long) = AIWriteEntry(UUID.randomUUID().toString(),
            now.atZone(tokyoZone).minusDays(daysAgo).toInstant().toEpochMilli() / 1000.0, summary, emptyList(), emptyList(), emptyList())
        val pruned = AIWriteLog.pruned(listOf(entry("八天前", 8), entry("七天前", 7), entry("六天前", 6), entry("今天", 0)), now, tokyoZone)
        assertEquals(listOf("六天前", "今天"), pruned.map { it.summary })
        assertEquals("a record eight days old is gone at the next launch", 0,
            AIWriteLog(prefs, now.plusSeconds(8 * 86_400L), tokyoZone).entries.value.size)

        val many = (0 until 205).map { index ->
            AIWriteEntry(UUID.randomUUID().toString(), now.toEpochMilli() / 1000.0 + index, "$index", emptyList(), emptyList(), emptyList())
        }
        val kept = AIWriteLog.pruned(many, now, tokyoZone)
        assertEquals(AIWriteLog.limit, kept.size)
        assertEquals("the oldest go first", "5", kept.first().summary)
    }

    @Test
    fun testTheKeptWritesReadBackTheSame() {
        val step = samplePlan("第一步", tokyo(23, 15)).copy(aiOrigin = AIWriteText.splitOrigin("准备季度汇报"))
        val original = samplePlan("准备季度汇报", tokyo(23, 10), 90, "work")
        val entry = AIWriteEntry("e1", 1_763_877_600.25, AIWriteText.breakdown(1, "准备季度汇报", archived = true),
            listOf(step), listOf(original), listOf(original.copy(isArchived = true)), undone = true)
        val text = AIWriteLog.encode(listOf(entry))
        assertEquals(listOf(entry), AIWriteLog.decode(text))
        assertEquals(setOf("id", "at", "summary", "added", "before", "after", "undone"),
            Json.parseToJsonElement(text).jsonArray[0].jsonObject.keys)
        assertTrue("an entry without its time is passed over", AIWriteLog.decode("""[{"id":"x","summary":"一"}]""").isEmpty())
        assertTrue(AIWriteLog.decode("{not json").isEmpty())
    }

    @Test
    fun testUndoingAWriteLeavesEditedPlansAlone() {
        val day = tokyo(23, 15)
        val first = samplePlan("第一步", day, 25, "work")
        val second = samplePlan("第二步", day, 25, "work")
        val original = samplePlan("交周报", day, 45, "work")
        val moved = original.copy(scheduledStart = iso(day.plusDays(1)))
        val entry = AIWriteEntry(UUID.randomUUID().toString(), day.toEpochSecond().toDouble(), "写入", listOf(first, second),
            listOf(original), listOf(moved))
        assertEquals(3, entry.total)

        val changedSince = second.copy(title = "第二步（已改）")
        val snapshot = PlanSnapshot(checkInItems = listOf(first, changedSince, moved))
        val (after, undone) = entry.undo.apply(snapshot)
        assertEquals(2, undone)
        assertEquals(listOf("交周报", "第二步（已改）").sorted(), after.checkInItems.map { it.title }.sorted())
        assertEquals(original.scheduledStart, after.checkInItems.first { it.id == original.id }.scheduledStart)
        assertEquals("已撤销 2 条；之后改过的保持不变", AIWriteText.undoResult(undone, entry.total))
    }

    @Test
    fun testOlderSettingsTurnTheNewCardsOn() {
        val values = Json.parseToJsonElement(AISettings(isEnabled = true).encode()).jsonObject.toMutableMap()
        for (key in listOf("eveningWrap", "weeklyLetter")) {
            assertNotNull(key, values.remove(key))
        }
        val decoded = requireNotNull(AISettings.decode(JsonObject(values).toString()))
        assertTrue(decoded.eveningWrap)
        assertTrue(decoded.weeklyLetter)
        val again = requireNotNull(AISettings.decode(AISettings(isEnabled = true, eveningWrap = false, weeklyLetter = false).encode()))
        assertFalse(again.eveningWrap)
        assertFalse(again.weeklyLetter)
    }
}

/** 计划的来源字段: optional, empty on old data, carried in snapshots and backups. */
class MojiAIOriginTests {
    @Test
    fun testOlderPlansHaveNoOrigin() {
        val old = """{"id":"${UUID.randomUUID()}","title":"交周报","scheduledStart":"2026-11-23T06:00:00Z","createdAt":"2026-11-23T06:00:00Z"}"""
        val plan = MojiJson.codec.decodeFromString(CheckInItem.serializer(), old)
        assertNull(plan.aiOrigin)
        val written = plan.copy(aiOrigin = AIWriteText.splitOrigin("准备季度汇报"))
        val text = MojiJson.codec.encodeToString(CheckInItem.serializer(), written)
        assertEquals(written, MojiJson.codec.decodeFromString(CheckInItem.serializer(), text))
        assertEquals("拆自「准备季度汇报」", Json.parseToJsonElement(text).jsonObject["aiOrigin"]?.jsonPrimitive?.content)
    }

    @Test
    fun testASnapshotCarriesTheOrigins() {
        val step = samplePlan("列出汇报的三个要点", tokyo(23, 16), 15, "work").copy(aiOrigin = AIWriteText.splitOrigin("准备季度汇报"))
        val mine = samplePlan("散步", tokyo(23, 18))
        val snapshot = PlanSnapshot(checkInItems = listOf(step, mine), lastUpdated = "2026-11-23T06:00:00Z")
        val (decoded, preferences) = MojiJson.decodeBackupOrSnapshot(MojiJson.codec.encodeToString(PlanSnapshot.serializer(), snapshot))
        assertNull(preferences)
        assertEquals(snapshot.checkInItems, decoded.checkInItems)
        assertEquals("拆自「准备季度汇报」", decoded.checkInItems.first { it.id == step.id }.aiOrigin)
        assertNull(decoded.checkInItems.first { it.id == mine.id }.aiOrigin)
    }
}

/** 分享面板的收件箱: what is shared comes back the same, cut at 6,000 characters. */
class MojiShareInboxTests {
    private val folder: File = Files.createTempDirectory("moji-share-inbox").toFile()

    @After
    fun removeTheFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun testWhatIsKeptComesBackTheSame() {
        val inbox = ShareInbox(File(folder, ShareInbox.folderName))
        val item = inbox.add("  周五前交季度报告，周三下午和小王对一下数据  ", null, Instant.ofEpochSecond(1_000))
        assertEquals("周五前交季度报告，周三下午和小王对一下数据", item.text)
        assertNull(item.image)
        assertEquals(item, inbox.items().firstOrNull { it.id == item.id })

        val long = inbox.add("字".repeat(6_100), null, Instant.ofEpochSecond(2_000))
        assertEquals(ShareInbox.textLimit, long.text?.length)
        assertEquals(6_000, inbox.items().firstOrNull { it.id == long.id }?.text?.length)
        assertEquals("oldest first", listOf(item.id, long.id), inbox.items().map { it.id })
        assertEquals(item.id, inbox.oldest()?.id)

        assertThrows("nothing to keep", IllegalArgumentException::class.java) { inbox.add("   ", null) }
        assertThrows(IllegalArgumentException::class.java) { inbox.add(null, ByteArray(0)) }
        assertEquals(2, inbox.items().size)

        inbox.remove(item)
        assertEquals(listOf(long.id), inbox.items().map { it.id })
        inbox.removeAll()
        assertTrue(inbox.items().isEmpty())
    }

    @Test
    fun testAPictureIsKeptBesideIt() {
        val inbox = ShareInbox(File(folder, ShareInbox.folderName))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val item = inbox.add(null, jpeg)
        assertNull(item.text)
        assertEquals("${item.id}.jpg", item.image)
        assertArrayEquals(jpeg, inbox.imageFile(item)?.readBytes())
        inbox.remove(item)
        assertNull(inbox.items().firstOrNull { it.id == item.id })
        assertNull("the picture goes with it", inbox.imageFile(item))
    }

    @Test
    fun testClearingEmptiesTheFolder() {
        assertEquals("share_inbox", ShareInbox.folderName)
        val directory = File(folder, ShareInbox.folderName)
        val inbox = ShareInbox(directory)
        inbox.add("一", byteArrayOf(1, 2, 3))
        File(directory, "half-written.json.tmp").writeText("{")
        inbox.clear()
        assertTrue(inbox.items().isEmpty())
        assertEquals(0, directory.listFiles()?.size ?: -1)
        assertTrue("an inbox that was never written is empty", ShareInbox(File(folder, "missing")).items().isEmpty())
    }
}
