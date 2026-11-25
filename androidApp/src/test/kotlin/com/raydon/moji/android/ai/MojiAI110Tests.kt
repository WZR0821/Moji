package com.raydon.moji.android.ai

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.MemoChecklistItem
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.PlanTemplate
import com.raydon.moji.core.PlanWorkflowUndo
import com.raydon.moji.core.TimeRecord
import com.raydon.moji.core.WeeklyReflection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 1.10.0 (spec 2026-11-26). Ported test by test from MojiAI110Tests at the end of
// MojiTests/MojiCoreTests.swift: the same samples and the same expectations. The few
// tests marked “Android only” cover Android code iOS has no counterpart for.
// The zone is fixed to Tokyo so every day is the same on any machine.
class MojiAI110Tests {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private fun date(text: String): Instant = Instant.parse(text)

    /** Thursday 2026-11-19, noon in Tokyo. */
    private val now: ZonedDateTime = date("2026-11-19T03:00:00Z").atZone(zone)
    private val today: LocalDate get() = now.toLocalDate()
    private val categories = listOf("study", "work")

    private fun day(text: String): LocalDate = LocalDate.parse(text)

    /** A plan the way the iOS samples build one: exactTime unless told otherwise. */
    private fun item(
        title: String,
        category: String,
        start: Instant,
        minutes: Int = 25,
        status: String = "planned",
        scheduleKind: String? = "exactTime",
        detailsConfigured: Boolean? = true,
        durationOn: Boolean? = null,
        completedAt: Instant? = null,
        createdAt: Instant = start,
        archived: Boolean? = false,
    ) = CheckInItem(
        id = UUID.randomUUID().toString(), title = title, category = category, scheduledStart = start.toString(),
        plannedMinutes = minutes, status = status, completedAt = completedAt?.toString(), createdAt = createdAt.toString(),
        scheduleKind = scheduleKind, detailsConfigured = detailsConfigured, plannedDurationEnabled = durationOn, isArchived = archived,
    )

    private val startOfToday: Instant get() = today.atStartOfDay(zone).toInstant()

    // MARK: A1 原文

    @Test
    fun testAChecklistKeepsTheTextItWasMadeFrom() {
        assertEquals("a checklist keeps its 原文 when saved", "下周六考试，前一天打印准考证。",
            MemoTextRules.checklistContent("  下周六考试，前一天打印准考证。\n  "))
        val saved = MemoEditState(true, "  下周六考试，前一天打印准考证。\n  ", listOf(MemoChecklistItem("a", "打印准考证")))
            .memo("m", "考前准备", false, "2026-11-19T00:00:00Z", "2026-11-19T00:00:00Z")
        assertEquals("checklist", saved.mode)
        assertEquals("下周六考试，前一天打印准考证。", saved.content)

        val items = listOf(MemoChecklistItem("a", "打印准考证"), MemoChecklistItem("b", "带两支笔", isCompleted = true))
        assertEquals("• 打印准考证\n✓ 带两支笔\n\n下周六考试。", MemoTextRules.noteText(items, "下周六考试。"))
        assertEquals("• 打印准考证\n✓ 带两支笔", MemoTextRules.noteText(items, "  "))
        assertEquals("下周六考试。", MemoTextRules.noteText(emptyList(), "下周六考试。"))

        val back = MemoEditState(true, "下周六考试。", items).turnedBackToText()
        assertFalse(back.checklist)
        assertEquals("下周六考试。", back.content)
        assertTrue(back.memo("m", "", false, "2026-11-19T00:00:00Z", "2026-11-19T00:00:00Z").checklistItems.isEmpty())
    }

    /** Android only: the editor state the memo page keeps while a drafted checklist is open. */
    @Test
    fun testTheEditorKeepsTheOriginalUntilItIsDropped() {
        val original = "下周六考试，前一天把准考证打印出来。\n还想再过一遍三个判例。"
        val drafted = MemoEditState(false, original, emptyList()).usingChecklistDraft(listOf("打印准考证", "复习三个判例"))
        assertTrue(drafted.checklist)
        assertEquals(original, drafted.original)
        assertEquals(listOf("打印准考证", "复习三个判例"), drafted.items.map { it.text })
        assertSame("nothing drafted, nothing changes", drafted, drafted.usingChecklistDraft(emptyList()))
        assertNull(drafted.droppingOriginal().original)
        assertEquals("• 打印准考证\n• 复习三个判例\n\n$original", drafted.checklistAsText().content)
    }

    // MARK: A2 首页输入

    @Test
    fun testRepeatWordsTakeTheirTailWithThem() {
        val sunday = date("2026-11-22T01:00:00Z").atZone(zone)
        fun adoptingAll(text: String): Pair<String, EntrySuggestion?> {
            val tokens = QuickEntryParser.tokens(text, sunday, categories, "study")
            val ids = tokens.map { it.id }.toSet()
            return QuickEntryParser.title(text, tokens, ids) to QuickEntryParser.suggestion(text, tokens, ids)
        }
        val (title, suggestion) = adoptingAll("明早八点背单词半小时，工作日都要")
        assertEquals("背单词", title)
        assertEquals("weekdays", suggestion?.repeatRule)
        assertEquals(30, suggestion?.minutes)
        assertEquals("exactTime", suggestion?.scheduleKind); assertEquals(8, suggestion?.hour)
        assertEquals(day("2026-11-23"), suggestion?.day)
        assertEquals("喝水", adoptingAll("每天都要喝水").first)
        assertEquals("晨跑", adoptingAll("晨跑，工作日都").first)
        assertEquals("交周报", adoptingAll("每周都得交周报").first)
    }

    @Test
    fun testTheLocalFallbackOffersOnlyWhatWouldChange() {
        val text = "明早八点背单词半小时，工作日都要"
        val tokens = QuickEntryParser.tokens(text, now, categories, "study")
        val plan = item(text, "study", startOfToday, scheduleKind = "allDay", detailsConfigured = false)
        val local = QuickEntryParser.localSuggestion(text, tokens)?.removingMatches(plan, zone)
        assertNotNull(local)
        assertFalse(local!!.isEmpty)
        assertEquals("背单词", local.title)
        assertEquals(listOf("明天", "08:00", "30 分钟", "每个工作日"), local.chips(today))
        assertNull("nothing read, nothing offered", QuickEntryParser.suggestion("背单词", emptyList(), emptySet()))
    }

    @Test
    fun testSplittingPutsTheReadFieldsOnEveryPart() {
        val tomorrow = today.plusDays(1)
        val source = item("明天下午三点买菜和取快递", "study", startOfToday, scheduleKind = "allDay", detailsConfigured = false)
        val suggestion = EntrySuggestion(day = tomorrow, scheduleKind = "exactTime", hour = 15, minute = 0, minutes = 45,
            category = "work", repeatRule = "weekly", parts = listOf("买菜", "取快递"))
        val (first, rest) = suggestion.split(source, AIWriteText.splitOrigin(source.title), now.toInstant(), zone)!!
        assertEquals("the first part takes the plan's place", source.id, first.id)
        assertEquals(listOf("买菜", "取快递"), listOf(first.title) + rest.map { it.title })
        for (plan in listOf(first) + rest) {
            val start = AIDrafting.instant(plan.scheduledStart)!!.atZone(zone)
            assertEquals(tomorrow, start.toLocalDate())
            assertEquals("exactTime", plan.scheduleKind)
            assertEquals(15, start.hour)
            assertEquals(45, AIDrafting.plannedDurationMinutes(plan))
            assertEquals("work", plan.category)
            assertEquals("weekly", plan.repeatRule)
        }
        assertNull(first.aiOrigin)
        assertEquals(listOf(AIWriteText.splitOrigin(source.title)), rest.map { it.aiOrigin })

        assertNull("one part is not a split", suggestion.copy(parts = listOf("买菜")).split(source))
    }

    @Test
    fun testASuggestionGoesStaleOnceThePlanChanges() {
        val start = date("2026-11-19T06:00:00Z")
        val plan = item("交周报", "work", start, minutes = 45, durationOn = true)
        val basis = EntryStamp.of(plan, zone)
        assertTrue(EntrySuggestionRecord(EntrySuggestion(), basis, isLocal = true).isCurrent(plan, zone))
        assertEquals("the note is not part of a suggestion", basis, EntryStamp.of(plan.copy(note = "带上数据"), zone))
        val changed = listOf(
            plan.copy(scheduledStart = start.plus(Duration.ofDays(1)).toString()),
            plan.copy(scheduledStart = start.plusSeconds(1_800).toString()),
            plan.copy(plannedMinutes = 60),
            plan.copy(category = "study"),
            plan.copy(repeatRule = "daily"),
            plan.copy(title = "交月报"),
            plan.copy(status = "completed"),
        )
        for (other in changed) assertFalse(other.toString(), basis == EntryStamp.of(other, zone))
    }

    // MARK: A3 下周不排进过去

    @Test
    fun testNextWeekDraftsNeverLandInThePast() {
        val wednesday = date("2026-11-18T03:00:00Z").atZone(zone).toLocalDate()
        val lastWeek = date("2026-11-11T03:00:00Z").atZone(zone).toLocalDate()
        val drafts = AIDrafting.nextWeekDrafts(
            """{"plans":[{"title":"周一","weekday":1},{"title":"周二","weekday":2},{"title":"周三","weekday":3},{"title":"周五","weekday":5}]}""",
            lastWeek, "study", categories, today = wednesday,
        )
        assertEquals(listOf(wednesday, wednesday, wednesday, day("2026-11-20")), drafts.map { it.day })

        assertTrue(AIDrafting.offersNextWeek(wednesday, wednesday))
        assertTrue(AIDrafting.offersNextWeek(lastWeek, wednesday))
        assertFalse(AIDrafting.offersNextWeek(date("2026-11-04T03:00:00Z").atZone(zone).toLocalDate(), wednesday))
        assertEquals("这一周还没有计划记录，没有可以拟的内容。", AIRequestError.emptyWeek.message)
    }

    // MARK: A4 分享的介绍只弹一次

    @Test
    fun testTheShareIntroductionComesOncePerItem() {
        val prefs = AIMemoryPreferences()
        val store = AIProactiveStore(prefs)
        assertTrue(store.shouldIntroduceShare("A"))
        store.markShareIntroduced("A")
        assertFalse(store.shouldIntroduceShare("A"))
        assertTrue(store.shouldIntroduceShare("B"))
        assertFalse("kept across launches", AIProactiveStore(prefs).shouldIntroduceShare("A"))

        assertFalse(store.isNextWeekOfferHandled("2026-11-16"))
        store.markNextWeekOfferHandled("2026-11-16")
        assertTrue(store.isNextWeekOfferHandled("2026-11-16"))
        assertFalse(store.isNextWeekOfferHandled("2026-11-09"))
    }

    /** Android only: with nothing set up, the first shared item not introduced yet, so a second share still gets its one. */
    @Test
    fun testTheNextShareToIntroduceIsTheFirstNotIntroducedYet() {
        val store = AIProactiveStore(AIMemoryPreferences())
        assertEquals("A", store.shareToIntroduce(listOf("A", "B")))
        store.markShareIntroduced("A")
        assertEquals("B", store.shareToIntroduce(listOf("A", "B")))
        store.markShareIntroduced("B")
        assertNull(store.shareToIntroduce(listOf("A", "B")))
    }

    // MARK: A5 “全部”是不限定

    @Test
    fun testAllMeansNoLimit() {
        assertEquals(LibraryFilter(), AIDrafting.libraryFilter("""{"keyword":"全部","category":"全部类型","status":"不限","period":"所有日期"}""", now, categories))
        assertEquals(LibraryFilter(category = "study"),
            AIDrafting.libraryFilter("""{"keyword":null,"category":"学习","status":"all","period":"all"}""", now, categories))
        assertThrows(AIRequestError::class.java) { AIDrafting.libraryFilter("""{"keyword":null,"status":"weird"}""", now, categories) }
    }

    // MARK: A8 复盘草稿里的数字

    private val record = "这一周：11月16日–11月22日\n完成 4 项，没做完 2 项，跳过 0 项；预计投入 120 分钟，实际专注 95 分钟。\n已完成：\n[1] 查中出教授 2019 年论文的页码（学习）"

    @Test
    fun testNumbersAreReadInDigitsAndInChinese() {
        assertEquals(listOf(95.0, 1.5, 4.0), ReflectionNumbers.numbers("完成了四件，专注 95 分钟，第 1.5 天"))
        assertEquals(listOf(2.0, 3.0), ReflectionNumbers.numbers("两件大事顺延了三天"))
        assertEquals("a numeral not before a unit is not a number", emptyList<Double>(), ReflectionNumbers.numbers("一直没开始，周四起"))
        assertEquals("十分 is 'very', not ten minutes", listOf(20.0), ReflectionNumbers.numbers("这周十分顺利，读了二十分钟"))
        assertEquals(listOf("一。", "二；", "三\n", "四"), ReflectionNumbers.sentences("一。二；三\n四"))
    }

    @Test
    fun testASentenceWithANumberTheRecordLacksIsTakenOut() {
        val plan = item("查中出教授 2019 年论文的页码", "study", now.toInstant(), minutes = 20)
        val answer = """{"note":"这周完成了 9 件。前半周完成了四件，比较稳；2019 年那篇论文的页码查好了。周三顺延了 2 件。","next_focus":"下周先做 12 件事。","evidence":[{"point":"论文页码查好了","plans":[1]}]}"""
        val draft = AIDrafting.reflection(answer, listOf(plan), record)
        assertEquals("前半周完成了四件，比较稳；2019 年那篇论文的页码查好了。周三顺延了 2 件。", draft.note)
        assertEquals("", draft.nextFocus)
        assertEquals(listOf(plan.id), draft.evidence.firstOrNull()?.planIds)

        val error = assertThrows(AIRequestError::class.java) {
            AIDrafting.reflection("""{"note":"完成了 9 件。专注 300 分钟。","next_focus":""}""", listOf(plan), record)
        }
        assertSame(AIRequestError.reflectionMismatch, error)
        assertEquals("这次起草的内容和本周记录对不上，再试一次。", AIRequestError.reflectionMismatch.message)
        // Without the record nothing is checked (the letter and older callers).
        assertEquals("完成了 9 件。", AIDrafting.reflection("""{"note":"完成了 9 件。"}""", listOf(plan)).note)
    }

    /** Android only: the [1] [2] … numbering the plans in the record are not facts the draft may repeat. */
    @Test
    fun testPlanNumbersInTheRecordAreNotAllowedNumbers() {
        val numbered = "这一周：11月16日–11月22日\n完成 4 项，没做完 2 项，跳过 0 项；预计投入 120 分钟，实际专注 95 分钟。\n已完成：\n[12] 背单词（学习）"
        assertFalse(12.0 in ReflectionNumbers.allowed(numbered))
        assertTrue(4.0 in ReflectionNumbers.allowed(numbered))
        assertEquals("完成了 4 项。", AIDrafting.reflection("""{"note":"完成了 4 项。完成了 12 件。"}""", emptyList(), numbered).note)
    }

    @Test
    fun testTheStatsLineUnderTheDraft() {
        assertEquals("本周排了 0 件，完成 0 件，没做完 0 件；专注 0 分钟", WeekStats(0, 0, 0, 0, 0).line)
        assertEquals("本周排了 6 件，完成 3 件，没做完 2 件；专注 3 小时 20 分钟", WeekStats(6, 3, 2, 1, 200).line)

        val done = item("背单词", "study", date("2026-11-17T01:00:00Z"), status = "completed", completedAt = date("2026-11-17T02:00:00Z"))
        val open = item("写周报", "work", date("2026-11-18T01:00:00Z"), minutes = 30)
        val skipped = item("跑步", "study", date("2026-11-18T01:00:00Z"), minutes = 30, status = "skipped")
        val carried = item("上上周的事", "work", date("2026-11-03T01:00:00Z"), minutes = 30)
        val snapshot = PlanSnapshot(
            checkInItems = listOf(done, open, skipped, carried),
            records = listOf(TimeRecord(UUID.randomUUID().toString(), "背单词", "study", "2026-11-17T01:00:00Z", "2026-11-17T01:45:00Z", checkInItemID = done.id)),
        )
        val week = AIDrafting.weekRecord(snapshot, today, now)
        assertEquals(WeekStats(4, 1, 2, 1, 45), week.stats)
        assertEquals("本周排了 4 件，完成 1 件，没做完 2 件；专注 45 分钟", week.stats.line)
        assertTrue("the same numbers the model is given", week.text.contains("完成 1 项，没做完 2 项，跳过 1 项"))
        // Kept with the draft when it comes back, so the line is not counted again while the page is open.
        assertEquals(week.stats, AIDrafting.checkedReflection("""{"note":"前半周很稳。"}""", week).stats)
    }

    // MARK: A11 (Android only: iOS checks its messages by hand)

    @Test
    fun testFailuresSayModelServiceNotAI() {
        assertEquals("无法连接模型服务，请检查网络和 API 地址。", AIRequestError.network.message)
        assertEquals("模型服务暂时不可用，请稍后重试。（HTTP 503）", AIRequestError.http(503).message)
    }

    // MARK: B1 拆成步骤的三个去向

    @Test
    fun testStepsWrittenIntoTheNote() {
        assertEquals("步骤：\n1. 列出要点\n2. 找齐数据", BreakdownText.noteWithSteps("", listOf("列出要点", "找齐数据")))
        assertEquals("先问老师。\n\n步骤：\n1. 列出要点", BreakdownText.noteWithSteps("  先问老师。 \n", listOf("列出要点")))
        assertEquals("把 2 步写进「准备季度汇报」的说明", AIWriteText.stepsIntoNote(2, "准备季度汇报"))
        assertEquals("把「准备季度汇报」拆成清单备忘（4 项）", AIWriteText.stepsIntoChecklist("准备季度汇报", 4))
        assertEquals("放下「归纳昨日待办」", AIWriteText.dropped("归纳昨日待办"))
        assertEquals("按预填把「打印准考证」转成计划", AIWriteText.memoHinted("打印准考证"))
        assertEquals("墨记按备忘预填", AIWriteText.mark(AIWriteText.memoHintOrigin))
        // The sheet's three choices and its confirm button.
        assertEquals(listOf("加成计划", "写进说明", "存成清单"), BreakdownDestination.entries.map { it.label })
        assertEquals(listOf("加入 3 条", "加入", "写进说明", "存成清单"), listOf(BreakdownDestination.Plans.confirmTitle(3),
            BreakdownDestination.Plans.confirmTitle(0), BreakdownDestination.Note.confirmTitle(3), BreakdownDestination.Checklist.confirmTitle(3)))
        assertEquals(listOf("这些是草稿，勾选的才会加入计划。", "这些是草稿，勾选的才会写进说明。", "这些是草稿，勾选的才会存进清单。"),
            BreakdownDestination.entries.map { it.draftHint })
    }

    @Test
    fun testUndoTakesBackANewMemoUnlessItWasEdited() {
        val memo = BreakdownText.checklistMemo("准备季度汇报", listOf("列出要点", "找齐数据"), now.toInstant())
        assertEquals("", memo.content)
        val undo = PlanDraftUndo(emptyList(), PlanWorkflowUndo(emptyList(), emptyList()), listOf(memo))
        assertEquals(1, undo.total)
        val (state, count) = undo.apply(PlanSnapshot(memos = listOf(memo)))
        assertEquals(1, count)
        assertTrue(state.memos.isEmpty())

        val edited = memo.copy(checklistItems = memo.checklistItems.mapIndexed { index, it -> if (index == 0) it.copy(isCompleted = true) else it })
        val (kept, none) = undo.apply(PlanSnapshot(memos = listOf(edited)))
        assertEquals(0, none)
        assertEquals("a memo changed since stays", listOf(edited), kept.memos)
    }

    @Test
    fun testOlderWritesDecodeWithoutMemos() {
        val plan = item("交周报", "work", now.toInstant(), minutes = 30)
        val entry = AIWriteEntry(UUID.randomUUID().toString(), now.toEpochSecond().toDouble(), "拟下周 1 条", listOf(plan), emptyList(), emptyList())
        val text = AIWriteLog.encode(listOf(entry))
        assertFalse("an entry without memos is written as before", text.contains("addedMemos"))
        val old = AIWriteLog.decode(text).single()
        assertEquals(entry.id, old.id); assertEquals("拟下周 1 条", old.summary)
        assertEquals(listOf(plan.id), old.added.map { it.id })
        assertTrue(old.addedMemos.isEmpty())
        assertEquals(1, old.total)

        val memo = MemoItem(id = UUID.randomUUID().toString(), title = "清单", mode = "checklist", createdAt = now.toInstant().toString(), updatedAt = now.toInstant().toString())
        val decoded = AIWriteLog.decode(AIWriteLog.encode(listOf(entry.copy(addedMemos = listOf(memo))))).single()
        assertEquals(listOf(memo.id), decoded.addedMemos.map { it.id })
        assertEquals(2, decoded.total)
    }

    /**
     * Android only: the snapshot is kept in Room as this JSON, and 今天的整理 keeps its entries the same way.
     * What a write remembers equals what is read back, so undo still finds the plans and the memo.
     */
    @Test
    fun testUndoStillMatchesAfterTheSnapshotIsStoredAndReadBack() {
        val stamp = Instant.parse("2026-11-19T03:00:00.123456789Z")
        val original = item("准备季度汇报", "work", startOfToday)
        val changed = original.copy(note = BreakdownText.noteWithSteps("", listOf("列出要点")))
        val added = item("列出要点", "work", stamp, createdAt = stamp).copy(aiOrigin = AIWriteText.splitOrigin("准备季度汇报"))
        val memo = MemoItem(id = UUID.randomUUID().toString(), title = "准备季度汇报", mode = "checklist",
            checklistItems = listOf(MemoChecklistItem(UUID.randomUUID().toString(), "列出要点")), createdAt = stamp.toString(), updatedAt = stamp.toString())
        val written = PlanSnapshot(checkInItems = listOf(changed, added), memos = listOf(memo), lastUpdated = stamp.toString())
        val stored = com.raydon.moji.core.MojiJson.decodeBackupOrSnapshot(
            com.raydon.moji.core.MojiJson.codec.encodeToString(PlanSnapshot.serializer(), written)).first
        assertEquals(written.checkInItems, stored.checkInItems)
        assertEquals(written.memos, stored.memos)
        val bar = PlanDraftUndo(listOf(added), PlanWorkflowUndo(listOf(original), listOf(changed)), listOf(memo))
        val log = AIWriteLog.decode(AIWriteLog.encode(listOf(AIWriteEntry("w", stamp.epochSecond.toDouble(), "s", listOf(added), listOf(original),
            listOf(changed), addedMemos = listOf(memo))))).single().undo
        for (undo in listOf(bar, log)) {
            val (after, count) = undo.apply(stored)
            assertEquals(3, count)
            assertEquals(listOf(original), after.checkInItems)
            assertTrue(after.memos.isEmpty())
        }
    }

    // MARK: B2 依据点开的计划行

    @Test
    fun testEvidenceLinesSayWhatBecameOfEachPlan() {
        val done = item("背单词", "study", date("2026-11-17T23:00:00Z"), status = "completed")
        val carried = item("归纳昨日待办", "work", date("2026-11-16T01:00:00Z"))
        val skipped = item("跑步", "study", date("2026-11-17T01:00:00Z"), status = "skipped")
        val todays = item("写周报", "work", date("2026-11-19T01:00:00Z"))
        assertEquals("背单词 · 11月18日 周三 · 已完成", ReflectionEvidenceText.line(done, now))
        assertEquals("归纳昨日待办 · 11月16日 周一 · 顺延 3 天", ReflectionEvidenceText.line(carried, now))
        assertEquals("跑步 · 11月17日 周二 · 已跳过", ReflectionEvidenceText.line(skipped, now))
        assertEquals("写周报 · 11月19日 周四 · 没做完", ReflectionEvidenceText.line(todays, now))
        assertEquals("11月22日 周日", ReflectionEvidenceText.monthDayWeekday(date("2026-11-22T03:00:00Z").atZone(zone).toLocalDate()))
    }

    // MARK: B3 复盘保存后的下周提议

    @Test
    fun testTheNextWeekOfferAfterASavedReflection() {
        fun reflection(note: String, nextFocus: String) = WeeklyReflection("r", "2026-11-16", note, nextFocus, "2026-11-19T00:00:00Z")
        val saved = reflection("前半周很稳。", "")
        fun offer(reflection: WeeklyReflection?, week: LocalDate = today, showsEntries: Boolean = true, handled: Boolean = false) =
            AIDrafting.showsNextWeekOffer(reflection, week, today, showsEntries, handled)
        assertTrue(offer(saved))
        assertTrue(offer(reflection(" ", "先拆第一步")))
        assertFalse(offer(null))
        assertFalse(offer(reflection(" ", "\n")))
        assertFalse("closed or taken up for that week", offer(saved, handled = true))
        assertFalse(offer(saved, showsEntries = false))
        assertTrue("last week", offer(saved, week = date("2026-11-12T03:00:00Z").atZone(zone).toLocalDate()))
        assertFalse("two weeks back", offer(saved, week = date("2026-11-05T03:00:00Z").atZone(zone).toLocalDate()))
    }

    // MARK: B5 备忘转计划的预填

    @Test
    fun testAPrefilledRowNeverFallsOnADayGone() {
        val hints = AIDrafting.conversionHints("""{"rows":[{"index":0,"date":"2026-11-01","category":"学习"},{"index":1,"offset_days":2}]}""",
            2, now, categories)
        assertEquals(ConversionHint(day("2026-11-19"), "study"), hints[0])
        assertEquals(day("2026-11-21"), hints[1]?.day)
    }

    // MARK: N1 同类历史用时

    private fun finished(category: String, minutes: Long, daysAgo: Long, status: String = "completed"): Pair<CheckInItem, TimeRecord> {
        val end = now.toInstant().minus(Duration.ofDays(daysAgo))
        val start = end.minus(Duration.ofMinutes(minutes))
        val plan = item("样本", category, start, status = status, completedAt = if (status == "completed") end else null)
        return plan to TimeRecord(UUID.randomUUID().toString(), "样本", category, start.toString(), end.toString(), checkInItemID = plan.id)
    }

    private fun snapshot(samples: List<Pair<CheckInItem, TimeRecord>>) =
        PlanSnapshot(checkInItems = samples.map { it.first }, records = samples.map { it.second })

    @Test
    fun testTheUsualLengthNeedsThreeFinishedPlans() {
        fun typical(vararg samples: Pair<CheckInItem, TimeRecord>) =
            PlanDurationHistory.minutes("study", snapshot(samples.toList()), now.toInstant())
        assertNull(typical(finished("study", 30, 1), finished("study", 40, 2)))
        assertEquals(30, typical(finished("study", 20, 1), finished("study", 30, 2), finished("study", 45, 3)))
        assertEquals("23 rounds to 25", 25, typical(finished("study", 22, 1), finished("study", 23, 2), finished("study", 47, 3)))
        assertEquals("an even count takes the mean of the middle two", 35,
            typical(finished("study", 20, 1), finished("study", 30, 2), finished("study", 40, 3), finished("study", 50, 4)))
        assertEquals(5, typical(finished("study", 2, 1), finished("study", 1, 2), finished("study", 2, 3)))
        assertEquals(240, typical(finished("study", 300, 1), finished("study", 400, 2), finished("study", 500, 3)))
    }

    @Test
    fun testOnlyTheSameTypeFinishedInSixtyDaysCounts() {
        val others = listOf(finished("work", 100, 1), finished("study", 100, 1, status = "planned"), finished("study", 100, 61))
        val three = listOf(finished("study", 20, 1), finished("study", 30, 2), finished("study", 45, 3))
        assertEquals(30, PlanDurationHistory.minutes("study", snapshot(three + others), now.toInstant()))
        assertNull(PlanDurationHistory.minutes("study", snapshot(three.take(2) + others), now.toInstant()))
        val noRecord = snapshot(three).let { it.copy(records = it.records.drop(1)) }
        assertNull("a plan without time records is not a sample", PlanDurationHistory.minutes("study", noRecord, now.toInstant()))
        // Only the ten finished most recently: the older ones at 100 minutes are not mixed in.
        val recent = (1L..10L).map { finished("study", 10, it) }
        val older = (11L..15L).map { finished("study", 100, it) }
        assertEquals(10, PlanDurationHistory.minutes("study", snapshot(older + recent), now.toInstant()))
        assertEquals("按以往的学习计划，约 45 分钟", PlanDurationHistory.footnote("study", 45))
    }

    // MARK: N2 回顾页的下一步

    @Test
    fun testTheNextStepIsThePlanCarriedLongest() {
        fun plan(title: String, daysAgo: Long, status: String = "planned") =
            item(title, "study", now.toInstant().minus(Duration.ofDays(daysAgo)), status = status)
        val list = listOf(plan("今天的", 0), plan("两天", 2), plan("三天甲", 3), plan("三天乙", 3), plan("专注中", 6, status = "inProgress"))
        val picked = ReviewNextStep.carried(list, now)
        assertEquals("the longest, and on a tie the one higher in the list", "三天甲", picked?.first?.title)
        assertEquals(3, picked?.second)
        assertNull(ReviewNextStep.carried(listOf(plan("今天的", 0)), now))
        assertEquals("下一步：先做「归纳昨日待办」（顺延 3 天）", ReviewNextStep.text("归纳昨日待办", 3))
    }

    // MARK: N3 存为模板提示

    @Test
    fun testTheTemplateHintCountsTitlesWrittenByHand() {
        fun written(title: String, daysAgo: Long, archived: Boolean = false) =
            item(title, "study", now.toInstant(), createdAt = now.toInstant().minus(Duration.ofDays(daysAgo)), archived = if (archived) true else null)
        val new = written("Read  Paper ", 0)
        val snapshot = PlanSnapshot(checkInItems = listOf(written("read paper", 3), written("READ PAPER", 20, archived = true),
            written("read paper", 70), written("写周报", 1), new))
        // An entry source other than typing (template, draft, recurrence, copy) is handWritten = false.
        fun count(handWritten: Boolean = true, dismissed: Set<String> = emptySet(), state: PlanSnapshot = snapshot) =
            TemplateHint.count(new, state, dismissed, handWritten, now.toInstant())
        assertEquals("read paper", TemplateHint.normalized("  Read   Paper "))
        assertEquals("two others in sixty days (archived too), and this one", 3, count())
        assertEquals("这件事写过 3 次了，存成模板，下次一点就有", TemplateHint.text(3))
        assertNull(count(handWritten = false))
        assertNull(count(dismissed = setOf("read paper")))
        val template = PlanTemplate("t", "Read paper", "study", createdAt = now.toInstant().toString())
        assertNull("already a template", count(state = snapshot.copy(planTemplates = listOf(template))))
        val once = snapshot.copy(checkInItems = snapshot.checkInItems.drop(1))
        assertNull("only one other", count(state = once))
        val repeating = snapshot.copy(checkInItems = snapshot.checkInItems.mapIndexed { index, it -> if (index == 0) it.copy(repeatRule = "daily") else it })
        assertNull("a repeating plan of that title already brings it back", count(state = repeating))
        val generated = once.copy(checkInItems = once.checkInItems + listOf(
            written("read paper", 2).copy(generatedFromOccurrenceID = UUID.randomUUID().toString()),
            written("read paper", 1).copy(generatedFromOccurrenceID = UUID.randomUUID().toString()),
        ))
        assertNull("occurrences a repeat made were not written by hand", count(state = generated))

        assertEquals(listOf("read paper"), TemplateHint.dismissing(TemplateHint.dismissing(emptyList(), "Read  Paper"), "read paper"))
    }

    // MARK: N4 倒数日图标

    @Test
    fun testCountdownIconsFollowTheTitle() {
        val samples = listOf(
            "妈妈生日" to "gift", "期末考试" to "pencil.and.outline", "早大面试" to "person.2", "去东京旅行" to "airplane",
            "项目上线 DDL" to "flag.fill", "结婚纪念日" to "heart.fill", "毕业典礼" to "graduationcap", "开学报到" to "book",
            "搬家" to "house", "体检复查" to "cross.case", "周杰伦演唱会" to "music.note", "东京马拉松" to "figure.run",
            "春节回家" to "fireworks", "生日那天考试" to "gift", "随便一天" to null,
        )
        for ((title, symbol) in samples) assertEquals(title, symbol, CountdownSymbols.matched(title))
        assertEquals("hourglass", CountdownSymbols.withFallback("随便一天", "hourglass"))
        assertEquals("seal", CountdownSymbols.withFallback("随便一天", "seal"))
        assertEquals("flag.fill", CountdownSymbols.withFallback("交付 ddl", "seal"))
        assertEquals(setOf("hourglass", "seal"), CountdownSymbols.defaults)
    }

    /** Android only: which symbol the editor saves (iOS keeps this rule inside CountdownEditorView). */
    @Test
    fun testAnEditedDayFollowsANewTitleOnlyWhileItWearsADefault() {
        assertEquals("hourglass", CountdownSymbols.symbol("随便一天", past = false))
        assertEquals("seal", CountdownSymbols.symbol("随便一天", past = true))
        assertEquals("gift", CountdownSymbols.symbol("妈妈生日", past = true))
        assertEquals("graduationcap", CountdownSymbols.symbol("毕业典礼", false, existing = "hourglass", existingTitle = "典礼"))
        assertEquals("gift", CountdownSymbols.symbol("毕业典礼", false, existing = "gift", existingTitle = "典礼"))
        assertEquals("the title did not change", "hourglass", CountdownSymbols.symbol("妈妈生日", false, existing = "hourglass", existingTitle = "妈妈生日"))
    }
}
