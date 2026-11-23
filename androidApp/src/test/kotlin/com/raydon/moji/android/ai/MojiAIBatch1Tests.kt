package com.raydon.moji.android.ai

import com.raydon.moji.android.ai.QuickEntryToken.Kind
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// AI batch 1 (11-22): ported from MojiQuickEntryParserTests, MojiDailyBriefTests,
// MojiAIDailyStateTests and MojiAIProactiveSettingsTests in
// MojiTests/MojiCoreTests.swift, with the same samples, so a sentence gives the
// same chips and the brief the same words on both platforms. The zone is fixed
// to Tokyo; now is Sunday 2026-11-22, 10:00 there.

private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
private val sunday: ZonedDateTime = Instant.parse("2026-11-22T01:00:00Z").atZone(tokyo)

class MojiQuickEntryParserTests {
    private val now = sunday

    private data class Sample(val text: String, val chips: List<String>, val day: String?, val title: String)

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private fun read(text: String, categories: List<String> = listOf("study", "work")): List<QuickEntryToken> =
        QuickEntryParser.tokens(text, now, categories, "study")

    private fun adoptingAll(text: String): String {
        val tokens = read(text)
        return QuickEntryParser.title(text, tokens, tokens.map { it.id }.toSet())
    }

    private fun List<QuickEntryToken>.of(kind: Kind): QuickEntryToken? = firstOrNull { it.kind == kind }

    @Test
    fun testTheSharedSentencesGiveTheSameChipsAndTitles() {
        val samples = listOf(
            Sample("周三下午三点交周报 45分钟", listOf("周三", "15:00", "45 分钟"), "2026-11-25", "交周报"),
            Sample("明天下午三点交保险计划书 45分钟", listOf("明天", "15:00", "45 分钟"), "2026-11-23", "交保险计划书"),
            Sample("今晚8点跑步半小时", listOf("20:00", "30 分钟"), null, "跑步"),
            Sample("明早背单词", listOf("明天", "上午"), "2026-11-23", "背单词"),
            Sample("后天上午开组会", listOf("后天", "上午"), "2026-11-24", "开组会"),
            Sample("12月5日交论文", listOf("12月5日"), "2026-12-05", "交论文"),
            Sample("3号还书", listOf("12月3日"), "2026-12-03", "还书"),
            Sample("每天读书20分钟", listOf("20 分钟", "每天"), null, "读书"),
            Sample("每周三上午开例会", listOf("周三", "上午", "每周"), "2026-11-25", "开例会"),
            Sample("工作日早上8点打卡", listOf("08:00", "每个工作日"), null, "打卡"),
            Sample("下周一 9:30 面试 1小时", listOf("下周一", "09:30", "1 小时"), "2026-11-23", "面试"),
            Sample("两点半开会", listOf("14:30"), null, "开会"),
            Sample("一个半小时整理笔记", listOf("1 小时 30 分钟"), null, "整理笔记"),
            Sample("下午三点前交报告", listOf("15:00"), null, "交报告"),
            Sample("晚上去健身", listOf("晚上"), null, "去健身"),
            Sample("1.5小时写作", listOf("1 小时 30 分钟"), null, "写作"),
            Sample("中午吃饭", listOf("12:00"), null, "吃饭"),
            Sample("复习工作笔记", listOf("工作"), null, "复习工作笔记"),
        )
        for (sample in samples) {
            val tokens = read(sample.text)
            assertEquals(sample.text, sample.chips, tokens.map { it.text })
            assertEquals(sample.text, sample.day?.let { day(it) }, tokens.of(Kind.Day)?.day)
            assertEquals(sample.text, sample.title, adoptingAll(sample.text))
        }
    }

    @Test
    fun testChipsCarryTheValuesTheyShow() {
        val weekly = read("每周三上午开例会")
        assertEquals("weekly", weekly.of(Kind.Repeat)?.repeatRule)
        assertEquals("morning", weekly.of(Kind.Time)?.scheduleKind)

        val workdays = read("工作日早上8点打卡")
        val clock = workdays.of(Kind.Time)
        assertNotNull(clock); clock!!
        assertEquals("exactTime", clock.scheduleKind)
        assertEquals(8, clock.hour); assertEquals(0, clock.minute)
        assertEquals("weekdays", workdays.of(Kind.Repeat)?.repeatRule)
        assertNull("工作 inside 工作日 is not a type", workdays.of(Kind.Category))

        assertEquals(90, read("一个半小时整理笔记").first().minutes)
        assertEquals(90, read("1.5小时写作").first().minutes)
        assertEquals(30, read("今晚8点跑步半小时").last().minutes)
        val evening = read("今晚8点跑步半小时").first()
        assertEquals("exactTime", evening.scheduleKind); assertEquals(20, evening.hour)
        val noon = read("中午吃饭").first()
        assertEquals("exactTime", noon.scheduleKind); assertEquals(12, noon.hour)
        assertEquals("evening", read("晚上去健身").first().scheduleKind)
        assertEquals("morning", read("后天上午开组会").last().scheduleKind)
        val type = read("复习工作笔记").first()
        assertEquals("work", type.category)
        assertNull("a type keeps its words in the title", type.span)
    }

    @Test
    fun testWordsThatOnlyLookLikeTimesGiveNoChips() {
        val texts = listOf(
            "复习学习笔记", "提三点意见", "多读一点书", "十分重要的事", "考了90分", "周报", "写第三点", "一周三次",
            "今天天气好", "今天把明天的事做完", "2月30日交房租", "16:305", "30minutes", "周日跑步", "22号交", "", "   ",
        )
        for (text in texts) {
            assertEquals(text, emptyList<String>(), read(text).map { it.text })
        }
    }

    @Test
    fun testTheExtraRulesBothPlatformsShare() {
        val samples = listOf(
            "1点开会" to listOf("13:00"),
            "一点半开会" to listOf("13:30"),
            "两个半小时跑步" to listOf("2 小时 30 分钟"),
            "2个半小时跑步" to listOf("2 小时 30 分钟"),
            "三点钟开会" to listOf("15:00"),
            "下午 3:30 开会" to listOf("15:30"),
            "明早 8点跑步" to listOf("明天", "08:00"),
            "跑步 45 分钟" to listOf("45 分钟"),
            "每周一次跑步" to listOf("每周"),
            "写工作日志" to listOf("工作"),
            "下午三点二十分钟练琴" to listOf("15:00", "20 分钟"),
            "晚上12点睡觉" to listOf("晚上"),
            "明天和后天" to listOf("明天"),
            "6:30起床" to listOf("18:30"),
            "跑步30min" to listOf("30 分钟"),
            "跑步30 Mins" to listOf("30 分钟"),
            "下周日跑步" to listOf("下周日"),
            "大后天出发" to listOf("大后天"),
            "11月20日续签" to listOf("11月20日"),
            "20号交房租" to listOf("12月20日"),
        )
        for ((text, chips) in samples) {
            assertEquals(text, chips, read(text).map { it.text })
        }
        assertEquals("开会", adoptingAll("三点钟开会"))
        assertEquals("开会", adoptingAll("下午 3:30 开会"))
        assertEquals("跑步", adoptingAll("明早 8点跑步"))
        assertEquals("练琴", adoptingAll("下午三点二十分钟练琴"))
        assertEquals("a day that has passed this year is next year's", day("2027-11-20"), read("11月20日续签").first().day)
        assertEquals(day("2026-12-20"), read("20号交房租").first().day)
        assertEquals(day("2026-11-29"), read("下周日跑步").first().day)

        assertEquals(listOf("custom:运动"), read("运动后工作", listOf("study", "work", "custom:运动")).mapNotNull { it.category })
        assertEquals(
            "at the same place the longer name wins",
            listOf("custom:工作日报"),
            read("写工作日报", listOf("study", "work", "custom:工作日报")).mapNotNull { it.category },
        )
    }

    @Test
    fun testSharedWordsLeaveOnlyWhenEveryChipOnThemIsAdopted() {
        val text = "明早背单词"
        val tokens = read(text)
        val date = tokens.of(Kind.Day)
        assertNotNull(date); date!!
        assertEquals(text, QuickEntryParser.title(text, tokens, setOf(date.id)))
        val suggestion = QuickEntryParser.suggestion(text, tokens, setOf(date.id))
        assertNotNull(suggestion); suggestion!!
        assertEquals(day("2026-11-23"), suggestion.day)
        assertNull("the sentence stays as typed", suggestion.title)
        assertNull(suggestion.scheduleKind)
        assertNull(QuickEntryParser.suggestion(text, tokens, emptySet()))
    }

    @Test
    fun testAdoptedChipsBecomeOneSuggestion() {
        val text = "明天下午三点交保险计划书 45分钟"
        val tokens = read(text)
        val suggestion = QuickEntryParser.suggestion(text, tokens, tokens.map { it.id }.toSet())
        assertNotNull(suggestion); suggestion!!
        assertEquals("交保险计划书", suggestion.title)
        assertEquals(day("2026-11-23"), suggestion.day)
        assertEquals("exactTime", suggestion.scheduleKind)
        assertEquals(15, suggestion.hour); assertEquals(0, suggestion.minute)
        assertEquals(45, suggestion.minutes)
        assertNull(suggestion.category); assertNull(suggestion.repeatRule)

        val length = tokens.of(Kind.Duration)
        assertNotNull(length); length!!
        val partial = QuickEntryParser.suggestion(text, tokens, setOf(length.id))
        assertNotNull(partial); partial!!
        assertEquals("only the words of the adopted chip go", "明天下午三点交保险计划书", partial.title)
        assertEquals(45, partial.minutes); assertNull(partial.day)

        val plan = CheckInItem(
            id = UUID.randomUUID().toString(),
            title = text,
            category = "study",
            scheduledStart = "2026-11-22T01:00:00Z",
            plannedMinutes = 25,
            createdAt = "2026-11-22T01:00:00Z",
            scheduleKind = "allDay",
            detailsConfigured = false,
            plannedDurationEnabled = false,
        )
        val saved = suggestion.applied(plan, tokyo)
        assertNotNull(saved); saved!!
        assertEquals("交保险计划书", saved.title)
        assertEquals(Instant.parse("2026-11-23T06:00:00Z"), AIDrafting.instant(saved.scheduledStart))
        assertEquals("exactTime", saved.scheduleKind ?: "exactTime")
        assertEquals(45, AIDrafting.plannedDurationMinutes(saved))
    }

    @Test
    fun testAChipKeepsItsIdentityWhileTypingGoesOn() {
        val first = read("周三交周报")
        assertEquals(first.first().id, read("周三交周报 45分钟").first().id)
        assertNotEquals(first.first().id, read("周四交周报").first().id)
    }

    @Test
    fun testNumbersInDigitsAndChineseNumerals() {
        assertEquals(15, QuickEntryParser.number("15"))
        assertEquals(15, QuickEntryParser.number("十五"))
        assertEquals(20, QuickEntryParser.number("二十"))
        assertEquals(2, QuickEntryParser.number("两"))
        assertEquals(120, QuickEntryParser.number("一百二十"))
        assertEquals(105, QuickEntryParser.number("一百零五"))
        assertNull(QuickEntryParser.number("二三"))
        assertNull(QuickEntryParser.number(""))
        assertNull(QuickEntryParser.number("半"))
        assertEquals(
            listOf("三", "十", "十一", "十二", "二十", "二十五", "九十九"),
            listOf(3, 10, 11, 12, 20, 25, 99).map { QuickEntryParser.chineseNumeral(it) },
        )
        assertEquals("100", QuickEntryParser.chineseNumeral(100))
    }
}

/** 今日提要 and 顺延旁批: the facts are counted on the device; only an order and one reason come from the model. */
class MojiDailyBriefTests {
    private val now = sunday
    private val today: LocalDate get() = now.toLocalDate()

    private fun plan(title: String, at: String, minutes: Int? = null, status: String = "planned") = CheckInItem(
        id = UUID.randomUUID().toString(),
        title = title,
        category = "study",
        scheduledStart = at,
        plannedMinutes = minutes ?: 25,
        status = status,
        createdAt = at,
        plannedDurationEnabled = minutes != null,
    )

    private fun countdown(title: String, at: String, pinned: Boolean = false) = CountdownEvent(
        id = UUID.randomUUID().toString(),
        title = title,
        targetDate = at,
        isPinned = pinned,
        createdAt = at,
    )

    private val countdowns: List<CountdownEvent>
        get() = listOf(
            countdown("项目交付", "2026-11-24T03:00:00Z"),
            countdown("考试", "2026-12-20T03:00:00Z"),
            countdown("过去的事", "2026-11-01T03:00:00Z"),
        )

    @Test
    fun testFactsLineIsCountedOnTheDevice() {
        val pending = listOf(
            plan("整理发布说明", "2026-11-22T05:00:00Z", minutes = 30),
            plan("回邮件", "2026-11-22T07:00:00Z", minutes = 45),
            plan("归纳昨日待办", "2026-11-19T00:00:00Z"),
        )
        val near = AIDailyBrief.countdowns(countdowns, now, AIDailyBrief.cardCountdownDays)
        assertEquals("项目交付", near.first().title); assertEquals(2, near.first().days)
        val carried = AIDailyBrief.carried(pending, now)
        assertEquals(listOf("归纳昨日待办"), carried.map { it.plan.title }); assertEquals(3, carried.first().days)
        assertTrue(AIDailyBrief.shouldShow(pending, countdowns, now))
        assertEquals(
            "今天 3 件，约 1 小时 15 分钟；离「项目交付」还有 2 天；「归纳昨日待办」已顺延 3 天。",
            AIDailyBrief.firstLine(pending, countdowns, now),
        )

        val single = listOf(plan("整理发布说明", "2026-11-22T05:00:00Z"))
        val tonight = listOf(countdown("项目交付", "2026-11-22T10:00:00Z"))
        assertEquals("今天 1 件；「项目交付」就是今天。", AIDailyBrief.firstLine(single, tonight, now))
        assertTrue(AIDailyBrief.shouldShow(single, tonight, now))

        val two = listOf(plan("A", "2026-11-22T05:00:00Z"), plan("B", "2026-11-22T06:00:00Z"))
        assertEquals(
            "今天 2 件；明天就是「项目交付」。",
            AIDailyBrief.firstLine(two, listOf(countdown("项目交付", "2026-11-23T03:00:00Z")), now),
        )

        val several = listOf(plan("A", "2026-11-21T00:00:00Z"), plan("写完研究计划书第三节和第四节", "2026-11-17T00:00:00Z"))
        assertEquals(
            "今天 2 件；「写完研究计划书第三节和…」等 2 件已顺延，最久 5 天。",
            AIDailyBrief.firstLine(several, emptyList(), now),
        )
    }

    @Test
    fun testTheCardNeedsTwoPlansOrSomethingToSay() {
        val alone = listOf(plan("读书", "2026-11-22T05:00:00Z"))
        assertFalse(AIDailyBrief.shouldShow(alone, emptyList(), now))
        assertFalse(AIDailyBrief.shouldShow(emptyList(), countdowns, now))
        assertTrue(AIDailyBrief.shouldShow(listOf(plan("读书", "2026-11-21T05:00:00Z")), emptyList(), now))
        assertTrue("a day within 14 days is worth a card", AIDailyBrief.shouldShow(alone, countdowns, now))
        assertFalse(
            "28 days away is not near enough for the card",
            AIDailyBrief.shouldShow(alone, listOf(countdown("考试", "2026-12-20T03:00:00Z")), now),
        )

        val pinned = listOf(countdown("甲", "2026-11-24T03:00:00Z"), countdown("乙", "2026-11-24T03:00:00Z", pinned = true))
        assertEquals(
            "a marked day first among equals",
            "乙",
            AIDailyBrief.countdowns(pinned, now, AIDailyBrief.cardCountdownDays).first().title,
        )
        assertTrue(AIDailyBrief.firstLine(alone, pinned, now).contains("离「乙」还有 2 天"))
    }

    @Test
    fun testTheModelsOrderIsCheckedBeforeUse() {
        val plans = listOf(plan("A", "2026-11-22T01:00:00Z"), plan("B", "2026-11-22T02:00:00Z"), plan("C", "2026-11-22T03:00:00Z"))
        val ids = plans.map { it.id }
        val first = AIDailyBrief.parse("""{"order":[3,1,2],"reason":"离交付只剩两天，先定稿。"}""", plans)
        assertEquals(listOf(ids[2], ids[0], ids[1]), first.order)
        assertEquals("离交付只剩两天，先定稿", first.reason)
        val loose = AIDailyBrief.parse("""{"order":[5,"x",true,1,1]}""", plans)
        assertEquals(ids, loose.order); assertNull(loose.reason)
        assertThrows(AIRequestError::class.java) { AIDailyBrief.parse("""{"order":[]}""", plans) }
        assertThrows(AIRequestError::class.java) { AIDailyBrief.parse("先做第一件", plans) }
        assertThrows(AIRequestError::class.java) { AIDailyBrief.parse("""{"order":[1]}""", emptyList()) }
        val fenced = AIDailyBrief.parse("```json\n{\"order\":[\"2\"],\"reason\":\"  \"}\n```", plans.take(2))
        assertEquals(listOf(ids[1], ids[0]), fenced.order); assertNull(fenced.reason)
    }

    @Test
    fun testOnlyTheDaysPlansAndNearDaysAreSent() {
        val pending = listOf(
            plan("整理发布说明", "2026-11-22T05:00:00Z", minutes = 30),
            plan("归纳昨日待办", "2026-11-19T00:00:00Z"),
            plan("背单词", "2026-11-22T00:30:00Z", status = "inProgress"),
        )
        val request = AIDailyBrief.request(pending, countdowns, now)
        assertEquals(
            listOf(
                "今天的计划：",
                "[1] 整理发布说明（14:00，30 分钟，学习）",
                "[2] 归纳昨日待办（09:00，学习，已顺延 3 天）",
                "[3] 背单词（09:30，学习，正在进行）",
                "最近的日子：项目交付（还有 2 天）、考试（还有 28 天）",
            ).joinToString("\n"),
            request.input,
        )
        assertEquals(pending, request.plans)

        // The instructions are word for word the ones in the shared spec (§4.3).
        val instructions = AIDailyBrief.instructions(now)
        assertEquals(
            AIDrafting.preface + "\n" +
                "任务：替用户排今天计划的先后，并说明为什么先做第一件。" + AIDrafting.todayLine(now) + "\n" +
                "有具体钟点的按钟点先后；和临近日子有关的、顺延多天的优先；不要编造输入里没有的事。\n" +
                "输出 JSON：{\"order\":[计划编号…],\"reason\":\"…\"}\n" +
                "order 列出全部编号，第一个是建议先做的那件。reason 不超过 24 个字，说为什么先做它，" +
                "可以提到离哪个日子还有几天、已经顺延几天；不要复述标题，不要夸奖，不要用感叹号。",
            instructions,
        )

        // The answer shown on QA screens goes through the same reading.
        val parsed = AIDailyBrief.parse(AIQAFixture.answer(instructions, request.input), request.plans)
        assertEquals(pending.map { it.id }, parsed.order)
        assertNotNull(parsed.reason)

        // At most 20 plans with 60 characters of title each, and three days within 30 days.
        val many = (1..25).map { plan("计划$it", "2026-11-22T05:00:00Z") }
        val big = AIDailyBrief.request(many, emptyList(), now)
        assertEquals(20, big.plans.size)
        assertEquals(21, big.input.split("\n").size)
        assertFalse(big.input.contains("最近的日子"))
        val long = plan("长".repeat(80), "2026-11-22T05:00:00Z")
        assertTrue(AIDailyBrief.request(listOf(long), emptyList(), now).input.endsWith("[1] " + "长".repeat(60) + "（14:00，学习）"))
        val days = listOf(
            countdown("甲", "2026-11-22T10:00:00Z"),
            countdown("乙", "2026-11-23T03:00:00Z"),
            countdown("丙", "2026-11-30T03:00:00Z"),
            countdown("丁", "2026-12-10T03:00:00Z"),
        )
        assertTrue(AIDailyBrief.request(pending, days, now).input.endsWith("最近的日子：甲（就是今天）、乙（还有 1 天）、丙（还有 8 天）"))
    }

    @Test
    fun testTheListFollowsTheOrderAndTheAdviceFollowsWhatIsLeft() {
        val a = plan("A", "2026-11-22T01:00:00Z"); val b = plan("B", "2026-11-22T02:00:00Z")
        val c = plan("C", "2026-11-22T03:00:00Z"); val d = plan("D", "2026-11-22T04:00:00Z")
        assertEquals(listOf("C", "A", "B", "D"), AIDailyBrief.sorted(listOf(a, b, c, d), listOf(c.id, a.id)).map { it.title })
        assertEquals(listOf("A", "B"), AIDailyBrief.sorted(listOf(a, b), emptyList()).map { it.title })
        assertEquals(
            listOf("B", "A"),
            AIDailyBrief.sorted(listOf(a, b), listOf(b.id, b.id, UUID.randomUUID().toString())).map { it.title },
        )

        val key = AIDailyBrief.dayKey(today)
        val cache = AIProactiveStore.BriefCache(key, listOf(c.id, a.id, b.id), "离项目交付只剩两天")
        assertEquals(c.id, AIDailyBrief.firstPending(cache, listOf(a, b, c), today)?.id)
        assertEquals("宜先做「C」，离项目交付只剩两天。", AIDailyBrief.secondLine(cache, listOf(a, b, c), today))
        assertEquals(a.id, AIDailyBrief.firstPending(cache, listOf(a, b), today)?.id)
        assertEquals("the reason was for the plan already done", "宜先做「A」。", AIDailyBrief.secondLine(cache, listOf(a, b), today))
        assertNull(AIDailyBrief.firstPending(cache, emptyList(), today))
        assertNull(AIDailyBrief.secondLine(cache, emptyList(), today))
        assertNull(AIDailyBrief.firstPending(cache.copy(order = emptyList()), listOf(a), today))
        assertNull("yesterday's answer is not used", AIDailyBrief.secondLine(cache, listOf(a, b, c), today.plusDays(1)))
        assertNull(AIDailyBrief.secondLine(null, listOf(a, b, c), today))

        val long = plan("写完研究计划书第三节和第四节", "2026-11-22T05:00:00Z")
        assertEquals(
            "the advice keeps the whole title",
            "宜先做「写完研究计划书第三节和第四节」。",
            AIDailyBrief.secondLine(AIProactiveStore.BriefCache(key, listOf(long.id), null), listOf(long), today),
        )
        val notes = plan("整理发布说明", "2026-11-22T05:00:00Z")
        assertEquals(
            "宜先做「整理发布说明」，离项目交付只剩两天。",
            AIDailyBrief.secondLine(AIProactiveStore.BriefCache(key, listOf(notes.id), "离项目交付只剩两天"), listOf(notes), today),
        )
    }

    @Test
    fun testCarryNoteWordsCountTheDays() {
        assertEquals(3, AIDailyBrief.carryNoteDays)
        assertEquals("顺延三天了，拆小一点 ›", AIDailyBrief.carryNoteText(3))
        assertEquals("顺延十天了，拆小一点 ›", AIDailyBrief.carryNoteText(10))
        assertEquals("顺延十一天了，拆小一点 ›", AIDailyBrief.carryNoteText(11))
        assertEquals("顺延十二天了，拆小一点 ›", AIDailyBrief.carryNoteText(12))
        assertEquals("顺延二十五天了，拆小一点 ›", AIDailyBrief.carryNoteText(25))
        assertEquals("顺延九十九天了，拆小一点 ›", AIDailyBrief.carryNoteText(99))
        assertEquals("顺延 100 天了，拆小一点 ›", AIDailyBrief.carryNoteText(100))
    }
}

/** What 今日提要 and 顺延旁批 remember stays on this device, and only for its day. */
class MojiAIProactiveStoreTests {
    private val prefs = AIMemoryPreferences()
    private val now = sunday
    private val today: LocalDate get() = now.toLocalDate()
    private val tomorrow: LocalDate get() = today.plusDays(1)

    private fun plan(title: String) = CheckInItem(
        id = UUID.randomUUID().toString(),
        title = title,
        scheduledStart = "2026-11-22T05:00:00Z",
        createdAt = "2026-11-22T05:00:00Z",
    )

    @Test
    fun testTheKeysAreTheSharedOnes() {
        assertEquals("moji.ai.brief.v1", AIProactiveStore.briefKey)
        assertEquals("moji.ai.brief.attempt", AIProactiveStore.attemptKey)
        assertEquals("moji.ai.brief.dismissedDay", AIProactiveStore.dismissedDayKey)
        assertEquals("moji.ai.todayOrder.v1", AIProactiveStore.todayOrderKey)
        assertEquals("moji.ai.carryNotes.dismissed.v1", AIProactiveStore.carryDismissedKey)
        assertEquals(300, AIProactiveStore.carryDismissedLimit)
    }

    @Test
    fun testClosingTheCardLastsOneDay() {
        val store = AIProactiveStore(prefs)
        assertFalse(store.isDismissed(today))
        store.dismissBrief(AIDailyBrief.dayKey(today))
        assertTrue(store.isDismissed(today))
        assertFalse(store.isDismissed(tomorrow))
        assertTrue("kept across launches", AIProactiveStore(prefs).isDismissed(today))
    }

    @Test
    fun testTheListOrderIsForOneDayAndCanBeUndone() {
        val store = AIProactiveStore(prefs)
        val ids = List(3) { UUID.randomUUID().toString() }
        assertNull(store.order(today))
        store.saveTodayOrder(AIProactiveStore.TodayOrder(AIDailyBrief.dayKey(today), ids))
        assertEquals(ids, store.order(today))
        assertNull(store.order(tomorrow))
        assertEquals(ids, AIProactiveStore(prefs).order(today))
        store.saveTodayOrder(null)
        assertNull(store.order(today))
        assertNull(AIProactiveStore(prefs).order(today))
    }

    @Test
    fun testTheDaysAnswerIsReadOnlyOnItsOwnDay() {
        val a = plan("A"); val b = plan("B")
        prefs.putString(AIProactiveStore.briefKey, """{"day":"2026-11-22","order":["${b.id}","${a.id}","不是编号"],"reason":"先定稿"}""")
        val store = AIProactiveStore(prefs)
        assertEquals("先定稿", store.brief(today)?.reason)
        assertEquals(listOf(b.id, a.id, "不是编号"), store.brief(today)?.order)
        assertNull(store.brief(tomorrow))
        assertEquals("an id that is no plan is passed over", b.id, AIDailyBrief.firstPending(store.brief(today), listOf(a, b), today)?.id)

        store.saveBrief(AIProactiveStore.BriefCache("2026-11-22", listOf(a.id), null))
        val again = AIProactiveStore(prefs)
        assertEquals(listOf(a.id), again.brief(today)?.order)
        assertNull(again.brief(today)?.reason)

        prefs.putString(AIProactiveStore.briefKey, "{not json")
        assertNull("a damaged value reads as nothing", AIProactiveStore(prefs).brief(today))
    }

    @Test
    fun testWavedAwayNotesAreRememberedUpToALimit() {
        val store = AIProactiveStore(prefs)
        val ids = (0..AIProactiveStore.carryDismissedLimit).map { UUID.randomUUID().toString() }
        store.dismissCarryNote(ids[0])
        store.dismissCarryNote(ids[0])
        assertEquals(1, store.carryDismissed.value.size)
        for (id in ids.drop(1)) store.dismissCarryNote(id)
        assertEquals(AIProactiveStore.carryDismissedLimit, store.carryDismissed.value.size)
        assertFalse("the oldest goes first", store.isCarryNoteDismissed(ids[0]))
        assertTrue(store.isCarryNoteDismissed(ids.last()))
        assertEquals(AIProactiveStore.carryDismissedLimit, AIProactiveStore(prefs).carryDismissed.value.size)
    }

    @Test
    fun testNothingIsAskedWhileTheCardHasNoReasonToAsk() {
        // Every case below stops before a request would start, so no dispatcher is needed.
        val ai = AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore())
        val store = AIProactiveStore(prefs)
        val plans = listOf(plan("A"), plan("B"))
        store.refreshIfNeeded(plans, emptyList(), now, ai)
        assertFalse(store.loading.value)
        assertNull("not ready: nothing is sent", store.lastAttempt)

        ai.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "sk-test-123")
        ai.change { it.copy(isEnabled = true, dailyBrief = false) }
        assertTrue(ai.isReady)
        store.refreshIfNeeded(plans, emptyList(), now, ai)
        assertNull("the brief is switched off", store.lastAttempt)

        ai.change { it.copy(dailyBrief = true) }
        store.dismissBrief(AIDailyBrief.dayKey(today))
        store.refreshIfNeeded(plans, emptyList(), now, ai)
        assertNull("closed for today", store.lastAttempt)

        val answered = AIProactiveStore(AIMemoryPreferences())
        answered.saveBrief(AIProactiveStore.BriefCache(AIDailyBrief.dayKey(today), plans.map { it.id }, null))
        answered.refreshIfNeeded(plans, emptyList(), now, ai)
        assertNull("today's answer is already there", answered.lastAttempt)

        val waiting = AIProactiveStore(AIMemoryPreferences())
        waiting.lastAttempt = now.toEpochSecond() - 60
        waiting.refreshIfNeeded(plans, emptyList(), now, ai)
        assertEquals("a failed try waits half an hour", now.toEpochSecond() - 60, waiting.lastAttempt)
        assertFalse(waiting.loading.value)

        val quiet = AIProactiveStore(AIMemoryPreferences())
        quiet.refreshIfNeeded(listOf(plans[0]), emptyList(), now, ai)
        assertNull("one plan with nothing to say: no card, no request", quiet.lastAttempt)
        quiet.refreshIfNeeded(emptyList(), emptyList(), now, ai)
        assertNull(quiet.lastAttempt)
    }

    @Test
    fun testTheDayKeyIsTheCalendarDay() {
        assertEquals("2026-11-23", AIDailyBrief.dayKey(Instant.parse("2026-11-22T15:30:00Z").atZone(tokyo).toLocalDate()))
        assertEquals("2027-01-05", AIDailyBrief.dayKey(LocalDate.of(2027, 1, 5)))
    }
}

/** The switches for what Moji offers on its own, and the suggestion strip that only says what the plan does not. */
class MojiAIProactiveSettingsTests {
    @Test
    fun testOlderSettingsWithoutTheNewSwitchesTurnThemOn() {
        val values = Json.parseToJsonElement(AISettings(isEnabled = true).encode()).jsonObject.toMutableMap()
        for (key in listOf("showsEntriesWhenOff", "dailyBrief", "carryOverNotes")) {
            assertNotNull(key, values.remove(key))
        }
        val decoded = AISettings.decode(JsonObject(values).toString())
        assertNotNull(decoded); decoded!!
        assertTrue(decoded.isEnabled)
        assertTrue(decoded.showsEntriesWhenOff); assertTrue(decoded.dailyBrief); assertTrue(decoded.carryOverNotes)

        val off = AISettings(isEnabled = true, showsEntriesWhenOff = false, dailyBrief = false, carryOverNotes = false)
        val again = AISettings.decode(off.encode())
        assertNotNull(again); again!!
        assertFalse(again.showsEntriesWhenOff); assertFalse(again.dailyBrief); assertFalse(again.carryOverNotes)
    }

    @Test
    fun testEntriesShowWhileOffUntilHidden() {
        val store = AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore())
        assertFalse(store.isReady)
        assertTrue("the entries are there before anything is set up", store.showsEntries)
        assertTrue(store.showsEntriesFlow.value)
        store.change { it.copy(showsEntriesWhenOff = false) }
        assertFalse(store.showsEntries)
        assertFalse(store.showsEntriesFlow.value)
        store.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "sk-test-123")
        store.change { it.copy(isEnabled = true) }
        assertTrue(store.isReady)
        assertTrue("once it is on, the entries are always there", store.showsEntries)
        assertTrue(store.showsEntriesFlow.value)
    }

    @Test
    fun testASuggestionDropsWhatThePlanAlreadySays() {
        // Monday 2026-11-23, 15:00 in Tokyo.
        val plan = CheckInItem(
            id = UUID.randomUUID().toString(),
            title = "交周报",
            category = "work",
            scheduledStart = "2026-11-23T06:00:00Z",
            plannedMinutes = 45,
            createdAt = "2026-11-22T01:00:00Z",
            scheduleKind = "exactTime",
            repeatRule = "weekly",
            plannedDurationEnabled = true,
        )
        val same = EntrySuggestion(
            title = "交周报", day = LocalDate.parse("2026-11-23"), scheduleKind = "exactTime", hour = 15, minute = 0,
            minutes = 45, category = "work", repeatRule = "weekly",
        )
        val left = same.removingMatches(plan, tokyo)
        assertTrue(left.isEmpty)
        assertNull(left.title)

        val other = EntrySuggestion(
            title = "交月报", day = LocalDate.parse("2026-11-24"), scheduleKind = "exactTime", hour = 16, minute = 0,
            minutes = 30, category = "study", repeatRule = "daily",
        )
        assertEquals(other, other.removingMatches(plan, tokyo))
        assertEquals(
            "another minute is a change",
            "exactTime",
            EntrySuggestion(scheduleKind = "exactTime", hour = 15, minute = 30).removingMatches(plan, tokyo).scheduleKind,
        )

        val morning = CheckInItem(
            id = UUID.randomUUID().toString(),
            title = "背单词",
            category = "study",
            scheduledStart = "2026-11-23T00:00:00Z",
            plannedMinutes = 25,
            createdAt = "2026-11-22T01:00:00Z",
            scheduleKind = "morning",
            plannedDurationEnabled = false,
        )
        assertNull(EntrySuggestion(scheduleKind = "morning", hour = 9).removingMatches(morning, tokyo).scheduleKind)
        assertEquals("evening", EntrySuggestion(scheduleKind = "evening").removingMatches(morning, tokyo).scheduleKind)
        assertEquals("the plan had no length yet", 25, EntrySuggestion(minutes = 25).removingMatches(morning, tokyo).minutes)
        assertNull(EntrySuggestion(repeatRule = "never").removingMatches(morning, tokyo).repeatRule)
        assertEquals(
            listOf("买菜", "取快递"),
            EntrySuggestion(parts = listOf("买菜", "取快递")).removingMatches(morning, tokyo).parts,
        )
    }
}
