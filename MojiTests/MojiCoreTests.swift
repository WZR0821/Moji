import XCTest
import UniformTypeIdentifiers
@testable import Moji

final class MojiQuickEditingTests: XCTestCase {
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Tokyo")!; return c }
    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
    private func plan() -> CheckInItem {
        var p = CheckInItem(title: "阅读", category: .study, scheduledStart: date("2026-10-12T05:30:00Z"), plannedMinutes: 45, note: "保留说明", plannedDurationEnabled: true)
        p.repeatRule = .weekly; p.reminderMinutesBefore = 15; p.status = .inProgress
        return p
    }
    func testContentPatchesLatestPlanWithoutResettingLiveState() throws {
        var latest = plan(); latest.status = .completed; latest.completedAt = date("2026-10-12T06:00:00Z")
        let updated = try XCTUnwrap(PlanQuickChange.content(title: " 新标题 ", note: " 新说明 ").applying(to: latest))
        var expected = latest; expected.title = "新标题"; expected.note = "新说明"; expected.detailsConfigured = true
        XCTAssertEqual(updated, expected)
    }
    func testDatePreservesWallTimeAndOtherSettings() throws {
        let original = plan()
        let updated = try XCTUnwrap(PlanQuickChange.date(date("2026-10-20T00:00:00Z")).applying(to: original, calendar: calendar))
        var expected = original; expected.scheduledStart = date("2026-10-20T05:30:00Z"); expected.detailsConfigured = true
        XCTAssertEqual(updated, expected)
    }
    func testDateHandlesSpringForwardWithoutLosingMinutes() throws {
        var c = calendar; c.timeZone = TimeZone(identifier: "America/New_York")!
        var original = plan(); original.scheduledStart = date("2026-03-07T07:30:00Z")
        let updated = try XCTUnwrap(PlanQuickChange.date(date("2026-03-08T12:00:00Z")).applying(to: original, calendar: c))
        XCTAssertEqual(updated.scheduledStart, date("2026-03-08T07:30:00Z"))
    }
    func testAllDayAndDurationAreIndependent() throws {
        let updated = try XCTUnwrap(PlanQuickChange.schedule(.allDay, hour: 14, minute: 30, estimatedMinutes: nil).applying(to: plan(), calendar: calendar))
        XCTAssertEqual(updated.scheduledStart, date("2026-10-11T15:00:00Z"))
        XCTAssertFalse(updated.hasPlannedDuration); XCTAssertEqual(updated.plannedMinutes, 45)
        XCTAssertEqual(updated.repeatRule, .weekly)
    }
    func testUnrelatedEditDoesNotEnableLegacyEstimate() throws {
        var original = plan(); original.detailsConfigured = false; original.plannedDurationEnabled = nil
        let updated = try XCTUnwrap(PlanQuickChange.category(.work).applying(to: original))
        XCTAssertFalse(updated.hasPlannedDuration); XCTAssertEqual(updated.scheduledStart, original.scheduledStart)
    }
    func testCustomWeekdaysRequireValidSelection() throws {
        XCTAssertNil(PlanQuickChange.recurrence(.customWeekdays, weekdays: [0, 8]).applying(to: plan()))
        XCTAssertEqual(PlanQuickChange.recurrence(.customWeekdays, weekdays: [6, 2, 8]).applying(to: plan())?.repeatWeekdays, [2, 6])
        XCTAssertEqual(PlanQuickChange.recurrence(.never, weekdays: [2]).applying(to: plan())?.repeatWeekdays, [])
    }
    func testEmptyContentAndCompletedRecordCannotBeQuickEdited() {
        XCTAssertNil(PlanQuickChange.content(title: " \n ", note: "note").applying(to: plan()))
        var original = plan(); original.kind = .completedLog
        XCTAssertNil(PlanQuickChange.content(title: "title", note: "note").applying(to: original))
    }
    func testReminderAndEstimateAreBounded() {
        XCTAssertNil(PlanQuickChange.reminder(nil).applying(to: plan())?.reminderMinutesBefore)
        XCTAssertEqual(PlanQuickChange.reminder(Int.max).applying(to: plan())?.reminderMinutesBefore, 10080)
        XCTAssertEqual(PlanQuickChange.schedule(.exactTime, hour: 12, minute: 0, estimatedMinutes: Int.max).applying(to: plan())?.plannedMinutes, 1440)
    }
}


final class MojiWorkflowTests: XCTestCase {
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Tokyo")!; return c }
    private func date(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    private var now: Date { date("2026-10-06T00:00:00Z") }
    private func plan() -> CheckInItem { CheckInItem(title: "阅读", category: .study, scheduledStart: date("2026-10-06T05:30:00Z"), plannedMinutes: 45, plannedDurationEnabled: true) }
    func testConversionRetainsMemoAndDeduplicatesWholeAndItems() {
        let a = MemoChecklistItem(text: "车票", isCompleted: true)
        let b = MemoChecklistItem(text: "充电器")
        let memo = MemoItem(title: "出行", mode: .checklist, checklistItems: [a, b])
        var state = PlanSnapshot(memos: [memo])
        let first = PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now)
        XCTAssertEqual(first, PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now))
        let single = PlanWorkflow.convertMemo(in: &state, memoID: memo.id, itemID: a.id, day: now)
        XCTAssertEqual(single, PlanWorkflow.convertMemo(in: &state, memoID: memo.id, itemID: a.id, day: now))
        XCTAssertEqual(state.checkInItems.count, 2); XCTAssertEqual(state.memos, [memo])
        XCTAssertTrue(state.checkInItems[0].note.contains("☑ 车票"))
        XCTAssertEqual(state.checkInItems[1].title, "车票")
    }
    func testMissingAndEmptyConversionAreNotCreated() {
        let memo = MemoItem(title: "", content: "")
        var state = PlanSnapshot(memos: [memo])
        XCTAssertNil(PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now))
        XCTAssertNil(PlanWorkflow.convertMemo(in: &state, memoID: UUID(), day: now))
        XCTAssertTrue(state.checkInItems.isEmpty)
    }
    func testArchivedConversionRemainsLinked() {
        let memo = MemoItem(title: "出行", content: "车票")
        var state = PlanSnapshot(memos: [memo])
        let id = PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now)
        state.checkInItems[0].isArchived = true
        XCTAssertEqual(id, PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now))
        XCTAssertEqual(state.checkInItems.count, 1)
    }
    func testCopyResetsCompletionHistoryAndSeries() {
        var source = plan(); source.status = .completed; source.completedAt = now
        source.repeatRule = .daily; source.seriesID = UUID(); source.sourceMemoID = UUID()
        source.calendarEventIdentifier = "device"; source.isArchived = true
        let copied = PlanWorkflow.copied(source, to: now, calendar: calendar)
        XCTAssertNotEqual(source.id, copied.id); XCTAssertEqual(copied.status, .planned)
        XCTAssertEqual(copied.effectiveRepeatRule, .never); XCTAssertNil(copied.seriesID)
        XCTAssertNil(copied.completedAt); XCTAssertNil(copied.calendarEventIdentifier); XCTAssertNil(copied.sourceMemoID)
        XCTAssertEqual(copied.isArchived, false); XCTAssertEqual(copied.plannedMinutes, 45)
    }
    func testTemplateRetainsFieldsAndInstantiatesIndependentPlans() throws {
        var original = plan(); original.note = "第一章"; original.reminderMinutesBefore = 10
        let template = PlanTemplate(from: original, calendar: calendar)
        let restored = try JSONDecoder().decode(PlanTemplate.self, from: JSONEncoder().encode(template))
        XCTAssertEqual(template, restored)
        let one = template.plan(on: now, calendar: calendar), two = template.plan(on: now, calendar: calendar)
        XCTAssertNotEqual(one.id, two.id); XCTAssertEqual(one.note, "第一章"); XCTAssertEqual(one.reminderMinutesBefore, 10)
    }
    func testBatchDatePreservesTimeAndLeavesActiveAndCompletedAlone() {
        let p = plan(); var active = plan(); active.status = .inProgress
        var done = plan(); done.status = .completed
        let source = PlanSnapshot(checkInItems: [p, active, done]); var state = source
        let undo = PlanWorkflow.batch(in: &state, ids: Set([p.id, active.id, done.id]), day: date("2026-10-10T00:00:00Z"), calendar: calendar)
        XCTAssertEqual(state.checkInItems[0].scheduledStart, date("2026-10-10T05:30:00Z"))
        XCTAssertEqual(undo.before.count, 1); undo.apply(to: &state); XCTAssertEqual(state, source)
    }
    func testUndoPreservesSubsequentEditsAndOtherCollections() {
        let p = plan(); var state = PlanSnapshot(checkInItems: [p])
        let undo = PlanWorkflow.batch(in: &state, ids: [p.id], archived: true)
        let memo = MemoItem(title: "新备忘"); state.memos.append(memo)
        XCTAssertEqual(undo.apply(to: &state), 1); XCTAssertEqual(state.memos, [memo])
        let changed = PlanWorkflow.batch(in: &state, ids: [p.id], category: .work)
        state.checkInItems[0].title = "后续修改"
        XCTAssertEqual(changed.apply(to: &state), 0); XCTAssertEqual(state.checkInItems[0].title, "后续修改")
    }
    func testPausedFocusPlanIsNotArchived() {
        let p = plan(); var state = PlanSnapshot(checkInItems: [p])
        let undo = PlanWorkflow.batch(in: &state, ids: [p.id], archived: true, blockedID: p.id)
        XCTAssertTrue(undo.before.isEmpty); XCTAssertEqual(state.checkInItems, [p])
    }
    func testUndoAlsoProtectsPausedLinkedFocus() {
        let p = plan(); var state = PlanSnapshot(checkInItems: [p])
        let undo = PlanWorkflow.batch(in: &state, ids: [p.id], category: .work)
        XCTAssertEqual(undo.apply(to: &state, blockedID: p.id), 0)
        XCTAssertEqual(state.checkInItems[0].category, .work)
    }
    func testUntitledMemoUsesFirstNonblankLine() {
        let memo = MemoItem(content: "\n  \n阅读一章\n整理笔记")
        var state = PlanSnapshot(memos: [memo])
        _ = PlanWorkflow.convertMemo(in: &state, memoID: memo.id, day: now)
        XCTAssertEqual(state.checkInItems.first?.title, "阅读一章")
    }
    func testSubminuteFocusIsRoundedOnceAfterAggregation() {
        let records = (1...4).map { _ in TimeRecord(title: "阅读", category: .study, startDate: now, endDate: now.addingTimeInterval(45)) }
        XCTAssertEqual(WorkflowWeekSummary.make(snapshot: PlanSnapshot(records: records), week: now, calendar: calendar).actualMinutes, 3)
    }
    func testNextWeekMovesSameOccurrenceAndRetainsSeries() {
        var p = plan(); p.repeatRule = .daily; p.seriesID = UUID()
        var state = PlanSnapshot(checkInItems: [p])
        let undo = PlanWorkflow.batch(in: &state, ids: [p.id], nextWeekOf: now, calendar: calendar)
        XCTAssertEqual(state.checkInItems.count, 1); XCTAssertEqual(state.checkInItems[0].id, p.id)
        XCTAssertEqual(state.checkInItems[0].scheduledStart, date("2026-10-13T05:30:00Z"))
        XCTAssertEqual(state.checkInItems[0].seriesID, p.seriesID); undo.apply(to: &state); XCTAssertEqual(state.checkInItems, [p])
    }
    func testCheckedCompletionHasNoInventedActualDuration() {
        var p = plan(); p.status = .completed; p.completedAt = now
        let result = WorkflowWeekSummary.make(snapshot: PlanSnapshot(checkInItems: [p]), week: now, calendar: calendar)
        XCTAssertEqual(result.completed.count, 1); XCTAssertEqual(result.untrackedCount, 1)
        XCTAssertEqual(result.actualMinutes, 0); XCTAssertEqual(result.estimatedMinutes, 45)
    }
    func testFocusMinutesAreClippedAndCategoryFiltered() {
        let records = [TimeRecord(title: "阅读", category: .study, startDate: date("2026-10-04T14:30:00Z"), endDate: date("2026-10-04T15:30:00Z")),
                       TimeRecord(title: "工作", category: .work, startDate: date("2026-10-05T00:00:00Z"), endDate: date("2026-10-05T01:00:00Z"))]
        let result = WorkflowWeekSummary.make(snapshot: PlanSnapshot(records: records), week: now, category: .study, calendar: calendar)
        XCTAssertEqual(result.actualMinutes, 30)
    }
    func testSchema12UpgradeAndNewDataRoundTrip() throws {
        let legacy = Data("{\"schemaVersion\":12,\"records\":[],\"countdowns\":[],\"lastUpdated\":\"2026-10-06T00:00:00Z\"}".utf8)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        var state = try decoder.decode(PlanSnapshot.self, from: legacy)
        XCTAssertTrue(state.planTemplates.isEmpty); XCTAssertTrue(state.weeklyGoals.isEmpty)
        state.planTemplates = [PlanTemplate(from: plan())]
        state.weeklyGoals = [WeeklyGoal(weekStart: "2026-10-05", targetCount: 4, targetMinutes: 120)]
        state.weeklyReflections = [WeeklyReflection(weekStart: "2026-10-05", note: "保持节奏", nextFocus: "读完一章")]
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let result = try decoder.decode(PlanSnapshot.self, from: encoder.encode(state))
        XCTAssertEqual(result.weeklyGoals[0].targetCount, 4); XCTAssertEqual(result.weeklyReflections[0].nextFocus, "读完一章")
    }
    func testWeekKeysAndInvalidGoals() {
        XCTAssertEqual(PlanWorkflow.weekKey(date("2026-01-01T00:00:00Z"), calendar: calendar), "2025-12-29")
        XCTAssertTrue(PlanWorkflow.validWeekKey("2026-10-05")); XCTAssertFalse(PlanWorkflow.validWeekKey("2026-10-06"))
        XCTAssertFalse(PlanWorkflow.validWeekKey("2026-02-30"))
    }
}

final class MojiCoreTests: XCTestCase {
    func testBackupPickerAcceptsAutomaticAndManualBackupFiles() {
        let types = MojiBackupFile.readableContentTypes

        XCTAssertEqual(MojiBackupFile.contentType.identifier, "com.raydon.moji.backup")
        XCTAssertTrue(MojiBackupFile.contentType.conforms(to: .json))
        XCTAssertTrue(types.contains(MojiBackupFile.contentType))
        XCTAssertTrue(types.contains(.json))
        XCTAssertTrue(types.contains(.data))
        XCTAssertEqual(
            UTType(filenameExtension: "mojibackup")?.identifier,
            MojiBackupFile.identifier
        )
    }

    private func contractBackup(_ snapshot: PlanSnapshot) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let object = try JSONSerialization.jsonObject(with: encoder.encode(snapshot))
        return try JSONSerialization.data(withJSONObject: [
            "formatVersion": 1, "appIdentifier": "com.raydon.moji", "appVersion": "1.4.0",
            "exportedAt": "2026-10-05T09:00:00.123456789Z", "snapshot": object
        ])
    }

    func testBackupRejectsUnrelatedJsonAndBrokenArchives() {
        for json in ["{}", "[]", "null", "{\"notMoji\":true}", "{\"formatVersion\":1,\"snapshot\":{}}"] {
            XCTAssertThrowsError(try SharedPersistence.previewBackup(Data(json.utf8)))
        }
    }

    func testBackupRejectsWrongIdentityAndMissingArchiveFields() throws {
        var archive = try XCTUnwrap(JSONSerialization.jsonObject(with: contractBackup(.empty)) as? [String: Any])
        archive["appIdentifier"] = "other.app"
        XCTAssertThrowsError(try SharedPersistence.previewBackup(JSONSerialization.data(withJSONObject: archive)))
        archive.removeValue(forKey: "appIdentifier")
        XCTAssertThrowsError(try SharedPersistence.previewBackup(JSONSerialization.data(withJSONObject: archive)))
    }

    func testBackupSupportsFractionalDatesAndLegacyCompletedKind() throws {
        let date = Date(timeIntervalSince1970: 1_791_190_800)
        let item = CheckInItem(title: "旧安卓记录", category: .study, kind: .completedLog, scheduledStart: date, plannedMinutes: 25)
        let data = try contractBackup(PlanSnapshot(checkInItems: [item], lastUpdated: date))
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
            .replacingOccurrences(of: "completedLog", with: "completed")
        let preview = try SharedPersistence.previewBackup(Data(json.utf8))
        XCTAssertEqual(preview.checkInItems.first?.kind, .completedLog)
        // Android timestamps can carry subsecond precision in every date field.
        var archive = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var snapshot = try XCTUnwrap(archive["snapshot"] as? [String: Any])
        snapshot["lastUpdated"] = "2026-10-05T09:00:00.123Z"
        archive["snapshot"] = snapshot
        let fractional = try SharedPersistence.previewBackup(JSONSerialization.data(withJSONObject: archive))
        XCTAssertEqual(fractional.lastUpdated.timeIntervalSince1970.truncatingRemainder(dividingBy: 1), 0.123, accuracy: 0.001)
    }

    func testBackupPreservesNonPreciseZeroDurationAndRejectsInvalidPreciseRecord() throws {
        let date = Date(timeIntervalSince1970: 1000)
        let record = TimeRecord(title: "全天完成", category: .study, startDate: date, endDate: date, scheduleKind: .allDay)
        let preview = try SharedPersistence.previewBackup(contractBackup(PlanSnapshot(records: [record])))
        XCTAssertEqual(preview.records, [record])
        var precise = record
        precise.scheduleKind = .exactTime
        XCTAssertThrowsError(try SharedPersistence.previewBackup(contractBackup(PlanSnapshot(records: [precise]))))
    }

    func testBackupRejectsDuplicateIdsBeforeNormalization() throws {
        let date = Date(timeIntervalSince1970: 1000)
        let record = TimeRecord(title: "重复", category: .study, startDate: date, endDate: date.addingTimeInterval(60))
        XCTAssertThrowsError(try SharedPersistence.previewBackup(contractBackup(PlanSnapshot(records: [record, record]))))
    }

    func testImportedBackupClearsDeviceCalendarIdentifiers() throws {
        var item = CheckInItem(title: "计划", category: .study, scheduledStart: Date(), plannedMinutes: 25)
        item.calendarEventIdentifier = "123"
        var event = CountdownEvent(title: "纪念日", targetDate: Date())
        event.calendarEventIdentifier = "456"
        let preview = try SharedPersistence.previewBackup(contractBackup(PlanSnapshot(checkInItems: [item], countdowns: [event])))
        XCTAssertNil(preview.checkInItems.first?.calendarEventIdentifier)
        XCTAssertNil(preview.countdowns.first?.calendarEventIdentifier)
        XCTAssertEqual(item.calendarEventIdentifier, "123")
    }

    func testInvalidBackupCannotChangeDataOrPreferences() throws {
        let before = SharedPersistence.load()
        let focus = SharedPersistence.sharedDefaults.object(forKey: PomodoroStorageKeys.focusMinutes) as? Int
        XCTAssertThrowsError(try SharedPersistence.importBackup(Data("{\"notMoji\":true}".utf8)))
        XCTAssertEqual(SharedPersistence.load(), before)
        XCTAssertEqual(SharedPersistence.sharedDefaults.object(forKey: PomodoroStorageKeys.focusMinutes) as? Int, focus)
    }

    func testWeeklySummarySeparatesStudyAndWorkMinutes() throws {
        let calendar = Calendar.mojiISO
        let monday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 9)))
        let records = [
            TimeRecord(
                title: "学习",
                category: .study,
                startDate: monday,
                endDate: monday.addingTimeInterval(75 * 60)
            ),
            TimeRecord(
                title: "工作",
                category: .work,
                startDate: monday.addingTimeInterval(24 * 60 * 60),
                endDate: monday.addingTimeInterval(24 * 60 * 60 + 90 * 60)
            )
        ]

        let summary = WeeklyAnalytics.summary(records: records, containing: monday, calendar: calendar)

        XCTAssertEqual(summary.studyMinutes, 75)
        XCTAssertEqual(summary.workMinutes, 90)
        XCTAssertEqual(summary.totalMinutes, 165)
        XCTAssertEqual(summary.days.count, 7)
    }

    func testWeeklySummarySplitsRecordAtMidnight() throws {
        let calendar = Calendar.mojiISO
        let mondayLate = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 23, minute: 30)))
        let record = TimeRecord(
            title: "跨日任务",
            category: .work,
            startDate: mondayLate,
            endDate: mondayLate.addingTimeInterval(60 * 60)
        )

        let summary = WeeklyAnalytics.summary(records: [record], containing: mondayLate, calendar: calendar)

        XCTAssertEqual(summary.days[0].workMinutes, 30)
        XCTAssertEqual(summary.days[1].workMinutes, 30)
        XCTAssertEqual(summary.workMinutes, 60)
    }

    func testCountdownPartsUseMinutePrecision() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let twoDays: TimeInterval = 2 * 24 * 60 * 60
        let threeHours: TimeInterval = 3 * 60 * 60
        let fifteenMinutes: TimeInterval = 15 * 60
        let target = now.addingTimeInterval(twoDays + threeHours + fifteenMinutes)
        let parts = CountdownCalculator.parts(until: target, from: now)

        XCTAssertFalse(parts.isPast)
        XCTAssertEqual(parts.days, 2)
        XCTAssertEqual(parts.hours, 3)
        XCTAssertEqual(parts.minutes, 15)
    }

    func testDurationRoundsPartialMinuteUp() {
        let start = Date(timeIntervalSince1970: 10_000)
        let record = TimeRecord(
            title: "短记录",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(61)
        )

        XCTAssertEqual(record.durationMinutes, 2)
    }

    func testLegacySnapshotDecodesWithoutChecklistData() throws {
        let json = """
        {
          "schemaVersion": 1,
          "records": [],
          "countdowns": [],
          "activeSession": null,
          "lastUpdated": "2026-07-23T00:00:00Z"
        }
        """.data(using: .utf8)!
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        let snapshot = try decoder.decode(PlanSnapshot.self, from: json)

        XCTAssertEqual(snapshot.schemaVersion, 1)
        XCTAssertTrue(snapshot.checkInItems.isEmpty)
        XCTAssertTrue(snapshot.records.isEmpty)
        XCTAssertTrue(snapshot.memos.isEmpty)
    }

    func testCheckInWeekSummaryCombinesCompletionAndActualMinutes() throws {
        let calendar = Calendar.mojiISO
        let monday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 9))
        )
        let first = CheckInItem(
            title: "复习",
            category: .study,
            scheduledStart: monday,
            plannedMinutes: 30,
            status: .completed,
            completedAt: monday.addingTimeInterval(30 * 60)
        )
        let second = CheckInItem(
            title: "写报告",
            category: .work,
            scheduledStart: monday.addingTimeInterval(60 * 60),
            plannedMinutes: 60
        )
        let actual = TimeRecord(
            title: first.title,
            category: first.category,
            startDate: monday,
            endDate: monday.addingTimeInterval(25 * 60),
            checkInItemID: first.id
        )

        let summary = CheckInAnalytics.week(
            items: [first, second],
            records: [actual],
            containing: monday,
            calendar: calendar
        )

        XCTAssertEqual(summary.plannedCount, 2)
        XCTAssertEqual(summary.completedCount, 1)
        XCTAssertEqual(summary.plannedMinutes, 90)
        XCTAssertEqual(summary.actualMinutes, 25)
        XCTAssertEqual(summary.completionRate, 0.5)
    }

    func testChecklistSessionHasCountdownTarget() {
        let start = Date(timeIntervalSince1970: 2_000_000)
        let session = ActiveSession(
            title: "写作",
            category: .work,
            startedAt: start,
            checkInItemID: UUID(),
            plannedDurationMinutes: 45
        )

        XCTAssertEqual(session.targetDate, start.addingTimeInterval(45 * 60))
    }

    func testPlanScheduleKindRoundTripsAndKeepsDisplayMeaning() throws {
        let date = Date(timeIntervalSince1970: 2_100_000)
        let item = CheckInItem(
            title: "全天整理资料",
            category: .study,
            scheduledStart: date,
            plannedMinutes: 40,
            scheduleKind: .allDay
        )
        let data = try JSONEncoder().encode(item)
        let decoded = try JSONDecoder().decode(CheckInItem.self, from: data)

        XCTAssertEqual(decoded.effectiveScheduleKind, .allDay)
        XCTAssertEqual(decoded.scheduleText, "全天")
    }

    func testLegacyPlanWithoutScheduleKindDefaultsToExactTime() throws {
        let item = CheckInItem(
            title: "旧版计划",
            category: .work,
            scheduledStart: Date(timeIntervalSince1970: 2_200_000),
            plannedMinutes: 30
        )
        let data = try JSONEncoder().encode(item)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        object.removeValue(forKey: "scheduleKind")
        let legacyData = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(CheckInItem.self, from: legacyData)

        XCTAssertEqual(decoded.effectiveScheduleKind, .exactTime)
    }

    func testCountdownUsesCalendarDaysInsteadOfHours() throws {
        let calendar = Calendar.mojiISO
        let lateToday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 23, minute: 50))
        )
        let earlyTomorrow = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 24, hour: 0, minute: 5))
        )

        let count = CountdownDayCalculator.count(
            to: earlyTomorrow,
            from: lateToday,
            calendar: calendar
        )

        XCTAssertEqual(count.signedDays, 1)
        XCTAssertEqual(count.fullText, "还有 1 天")
        XCTAssertEqual(count.directionText, "倒数")
    }

    func testCountdownSupportsCountUpForPastDates() throws {
        let calendar = Calendar.mojiISO
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 12))
        )
        let past = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 23))
        )

        let count = CountdownDayCalculator.count(to: past, from: now, calendar: calendar)

        XCTAssertTrue(count.isPast)
        XCTAssertEqual(count.magnitude, 3)
        XCTAssertEqual(count.fullText, "已经 3 天")
        XCTAssertEqual(count.directionText, "正数")
    }

    func testCountdownInclusiveDayAddsOneDay() throws {
        let calendar = Calendar.mojiISO
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23))
        )
        let target = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 25))
        )

        let count = CountdownDayCalculator.count(
            to: target,
            from: now,
            includesToday: true,
            calendar: calendar
        )

        XCTAssertEqual(count.signedDays, 3)
    }

    func testCountdownTodayHasDistinctDirection() throws {
        let calendar = Calendar.mojiISO
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 21))
        )

        let count = CountdownDayCalculator.count(to: now, from: now, calendar: calendar)

        XCTAssertTrue(count.isToday)
        XCTAssertEqual(count.directionText, "今天")
        XCTAssertEqual(count.fullText, "就是今天")
    }

    func testYearlyBirthdayUsesNextOccurrence() throws {
        let calendar = Calendar.mojiISO
        let birthday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 1990, month: 7, day: 22))
        )
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 12))
        )
        let event = CountdownEvent(
            title: "生日",
            targetDate: birthday,
            repeatRule: .yearly
        )

        let occurrence = event.occurrenceDate(relativeTo: now, calendar: calendar)
        let components = calendar.dateComponents([.year, .month, .day], from: occurrence)

        XCTAssertEqual(components.year, 2027)
        XCTAssertEqual(components.month, 7)
        XCTAssertEqual(components.day, 22)
    }

    func testRepeatingBirthdaySummaryDoesNotShowOriginalYear() throws {
        let calendar = Calendar.mojiISO
        let birthday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 1990, month: 9, day: 12))
        )
        let event = CountdownEvent(
            title: "生日",
            targetDate: birthday,
            repeatRule: .yearly
        )

        XCTAssertFalse(event.dateSummaryText.contains("1990"))
        XCTAssertTrue(event.dateSummaryText.contains("每年"))
    }

    func testLeapDayBirthdaySkipsInvalidYears() throws {
        let calendar = Calendar.mojiISO
        let birthday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2000, month: 2, day: 29))
        )
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 3, day: 1))
        )
        let event = CountdownEvent(
            title: "闰日生日",
            targetDate: birthday,
            repeatRule: .yearly
        )

        let occurrence = event.occurrenceDate(relativeTo: now, calendar: calendar)
        let components = calendar.dateComponents([.year, .month, .day], from: occurrence)

        XCTAssertEqual(components.year, 2028)
        XCTAssertEqual(components.month, 2)
        XCTAssertEqual(components.day, 29)
    }

    func testLegacyCountdownDefaultsToNonRepeatingAndNotSynced() throws {
        let event = CountdownEvent(
            title: "旧纪念日",
            targetDate: Date(timeIntervalSince1970: 2_300_000)
        )
        let data = try JSONEncoder().encode(event)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        object.removeValue(forKey: "repeatRule")
        object.removeValue(forKey: "includesToday")
        object.removeValue(forKey: "calendarSyncEnabled")
        object.removeValue(forKey: "calendarEventIdentifier")
        object.removeValue(forKey: "sortOrder")
        let legacyData = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(CountdownEvent.self, from: legacyData)

        XCTAssertEqual(decoded.effectiveRepeatRule, .never)
        XCTAssertFalse(decoded.countsToday)
        XCTAssertFalse(decoded.isSyncedToCalendar)
        XCTAssertNil(decoded.sortOrder)
    }

    func testDailyRecurringPlanCreatesNextOccurrenceAndKeepsReminder() throws {
        let calendar = Calendar.mojiISO
        let start = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 9, minute: 15))
        )
        let item = CheckInItem(
            title: "背单词",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 25,
            scheduleKind: .exactTime,
            repeatRule: .daily,
            reminderMinutesBefore: 15
        )

        let successor = try XCTUnwrap(item.recurringSuccessor(calendar: calendar))

        XCTAssertEqual(
            calendar.dateComponents([.day], from: start, to: successor.scheduledStart).day,
            1
        )
        XCTAssertEqual(successor.effectiveRepeatRule, .daily)
        XCTAssertEqual(successor.reminderMinutesBefore, 15)
        XCTAssertEqual(successor.recurrenceSeriesID, item.id)
    }

    func testGeneratedRecurringPlanOnlyAppearsWhenItsDateArrives() throws {
        let calendar = Calendar.mojiISO
        let firstDay = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 9))
        )
        var item = CheckInItem(
            title: "每日复盘",
            category: .study,
            scheduledStart: firstDay,
            plannedMinutes: 20,
            repeatRule: .daily
        )
        item.completedAt = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 10))
        )

        let successor = try XCTUnwrap(item.recurringSuccessor(calendar: calendar))
        let beforeDue = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 18))
        )
        let dueDay = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 24, hour: 7))
        )

        XCTAssertFalse(successor.isVisibleInChecklist(on: beforeDue, calendar: calendar))
        XCTAssertTrue(successor.isVisibleInChecklist(on: dueDay, calendar: calendar))
    }

    func testOverdueDailyCompletionSkipsMissedRepeatDates() throws {
        let calendar = Calendar.mojiISO
        let original = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 9))
        )
        var item = CheckInItem(
            title: "晨读",
            category: .study,
            scheduledStart: original,
            plannedMinutes: 25,
            repeatRule: .daily
        )
        item.completedAt = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 18))
        )

        let successor = try XCTUnwrap(item.recurringSuccessor(calendar: calendar))
        let components = calendar.dateComponents(
            [.year, .month, .day, .hour],
            from: successor.scheduledStart
        )

        XCTAssertEqual(components.year, 2026)
        XCTAssertEqual(components.month, 7)
        XCTAssertEqual(components.day, 24)
        XCTAssertEqual(components.hour, 9)
    }

    func testCountdownManualOrderReordersOnlyVisibleItems() {
        let first = CountdownEvent(
            title: "第一项",
            targetDate: Date(timeIntervalSince1970: 1_000),
            sortOrder: 0
        )
        let hidden = CountdownEvent(
            title: "另一分组",
            targetDate: Date(timeIntervalSince1970: 2_000),
            sortOrder: 1
        )
        let third = CountdownEvent(
            title: "第三项",
            targetDate: Date(timeIntervalSince1970: 3_000),
            sortOrder: 2
        )

        let reordered = CountdownOrdering.applyingVisibleOrder(
            [third.id, first.id],
            to: [first, hidden, third]
        )

        XCTAssertEqual(reordered.map(\.id), [third.id, hidden.id, first.id])
        XCTAssertEqual(reordered.map(\.effectiveSortOrder), [0, 1, 2])
    }

    func testCountdownUrgencyUsesStrictlyLessThanThreeDays() throws {
        let calendar = Calendar.mojiISO
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 12))
        )
        let inTwoDays = CountdownEvent(
            title: "两天后",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 2, to: now))
        )
        let inThreeDays = CountdownEvent(
            title: "三天后",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 3, to: now))
        )
        let yesterday = CountdownEvent(
            title: "昨天",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: -1, to: now))
        )

        XCTAssertTrue(inTwoDays.isUrgent(relativeTo: now, calendar: calendar))
        XCTAssertFalse(inThreeDays.isUrgent(relativeTo: now, calendar: calendar))
        XCTAssertFalse(yesterday.isUrgent(relativeTo: now, calendar: calendar))
    }

    func testCustomWeekdayPlanFindsNextSelectedWeekday() throws {
        let calendar = Calendar.mojiISO
        let friday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 24, hour: 18))
        )
        let item = CheckInItem(
            title: "力量训练",
            category: .study,
            scheduledStart: friday,
            plannedMinutes: 45,
            repeatRule: .customWeekdays,
            repeatWeekdays: [2, 4]
        )

        let next = try XCTUnwrap(item.nextOccurrenceStart(calendar: calendar))
        let components = calendar.dateComponents([.year, .month, .day, .hour], from: next)

        XCTAssertEqual(components.year, 2026)
        XCTAssertEqual(components.month, 7)
        XCTAssertEqual(components.day, 27)
        XCTAssertEqual(components.hour, 18)
    }

    func testRecurrenceEngineDoesNotDuplicateGeneratedOccurrence() throws {
        let calendar = Calendar.mojiISO
        let start = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 8))
        )
        let item = CheckInItem(
            title: "晨间计划",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 20,
            repeatRule: .daily
        )
        var items = [item]

        XCTAssertNotNil(
            PlanRecurrenceEngine.appendSuccessorIfNeeded(
                after: item,
                to: &items,
                calendar: calendar
            )
        )
        XCTAssertNil(
            PlanRecurrenceEngine.appendSuccessorIfNeeded(
                after: item,
                to: &items,
                calendar: calendar
            )
        )
        XCTAssertEqual(items.count, 2)
    }

    func testRecurrenceEngineRemovesGeneratedSuccessorWhenCompletionIsUndone() throws {
        let calendar = Calendar.mojiISO
        let start = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 8))
        )
        let completedAt = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 9))
        )
        let item = CheckInItem(
            title: "晨间计划",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 20,
            status: .completed,
            completedAt: completedAt,
            repeatRule: .daily
        )
        var items = [item]

        let successor = try XCTUnwrap(
            PlanRecurrenceEngine.appendSuccessorIfNeeded(
                after: item,
                to: &items,
                calendar: calendar
            )
        )
        XCTAssertEqual(successor.generatedFromOccurrenceID, item.id)

        let removed = PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
            after: item,
            from: &items,
            calendar: calendar
        )
        XCTAssertEqual(removed?.id, successor.id)
        XCTAssertEqual(items, [item])
    }

    func testMonthlySummaryCalculatesStreakAndCategoryTotals() throws {
        let calendar = Calendar.mojiISO
        let july21 = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 21, hour: 9))
        )
        let july22 = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 22, hour: 9))
        )
        let july23 = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 23, hour: 9))
        )
        let items = [july21, july22, july23].enumerated().map { index, date in
            CheckInItem(
                title: "计划 \(index)",
                category: index == 1 ? .work : .study,
                scheduledStart: date,
                plannedMinutes: 30,
                status: .completed,
                completedAt: date.addingTimeInterval(30 * 60)
            )
        }
        let records = [
            TimeRecord(
                title: "学习",
                category: .study,
                startDate: july21,
                endDate: july21.addingTimeInterval(30 * 60)
            ),
            TimeRecord(
                title: "工作",
                category: .work,
                startDate: july22,
                endDate: july22.addingTimeInterval(45 * 60)
            )
        ]

        let summary = CheckInAnalytics.month(
            items: items,
            records: records,
            containing: july23,
            now: july23.addingTimeInterval(12 * 60 * 60),
            calendar: calendar
        )

        XCTAssertEqual(summary.days.count, 31)
        XCTAssertEqual(summary.currentStreak, 3)
        XCTAssertEqual(summary.longestStreak, 3)
        XCTAssertEqual(summary.completedCount, 3)
        XCTAssertEqual(summary.studyMinutes, 30)
        XCTAssertEqual(summary.workMinutes, 45)
    }

    func testLegacyPlanDefaultsToNoRepeatAndNoReminder() throws {
        let item = CheckInItem(
            title: "旧计划",
            category: .work,
            scheduledStart: Date(timeIntervalSince1970: 2_400_000),
            plannedMinutes: 30
        )
        let data = try JSONEncoder().encode(item)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object.removeValue(forKey: "repeatRule")
        object.removeValue(forKey: "repeatWeekdays")
        object.removeValue(forKey: "seriesID")
        object.removeValue(forKey: "reminderMinutesBefore")
        let legacyData = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(CheckInItem.self, from: legacyData)

        XCTAssertEqual(decoded.effectiveRepeatRule, .never)
        XCTAssertTrue(decoded.effectiveRepeatWeekdays.isEmpty)
        XCTAssertNil(decoded.reminderMinutesBefore)
    }

    func testSnapshotNormalizationUpgradesSchemaWithoutDroppingData() {
        let item = CheckInItem(
            title: "保留我",
            category: .study,
            scheduledStart: Date(),
            plannedMinutes: 25
        )
        var snapshot = PlanSnapshot(schemaVersion: 1, checkInItems: [item])

        snapshot.normalize()

        XCTAssertEqual(snapshot.schemaVersion, PlanSnapshot.currentSchemaVersion)
        XCTAssertEqual(snapshot.checkInItems.map(\.id), [item.id])
    }

    func testPomodoroLiveControlsKeepPauseStateAndResetVisibility() throws {
        let suiteName = "com.raydon.moji.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(600, forKey: PomodoroStorageKeys.remaining)
        let startDate = Date(timeIntervalSince1970: 2_000_000)

        let running = PomodoroSharedState.toggle(at: startDate, defaults: defaults)
        XCTAssertTrue(running.isRunning)
        XCTAssertTrue(running.shouldShowLiveActivity)
        XCTAssertEqual(running.remainingSeconds, 600)
        XCTAssertEqual(
            running.targetDate?.timeIntervalSince1970,
            startDate.addingTimeInterval(600).timeIntervalSince1970
        )

        let paused = PomodoroSharedState.toggle(
            at: startDate.addingTimeInterval(90),
            defaults: defaults
        )
        XCTAssertFalse(paused.isRunning)
        XCTAssertTrue(paused.shouldShowLiveActivity)
        XCTAssertEqual(paused.remainingSeconds, 510)
        XCTAssertNil(paused.targetDate)

        let reset = PomodoroSharedState.reset(
            at: startDate.addingTimeInterval(100),
            defaults: defaults
        )
        XCTAssertFalse(reset.isRunning)
        XCTAssertFalse(reset.shouldShowLiveActivity)
        XCTAssertNil(reset.targetDate)
        XCTAssertEqual(reset.remainingSeconds, 25 * 60)
    }

    func testRunningPomodoroFromOlderBuildMigratesToVisibleLiveActivity() throws {
        let suiteName = "com.raydon.moji.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let now = Date(timeIntervalSince1970: 3_000_000)
        defaults.set(true, forKey: PomodoroStorageKeys.running)
        defaults.set(
            now.addingTimeInterval(300).timeIntervalSince1970,
            forKey: PomodoroStorageKeys.target
        )
        defaults.removeObject(forKey: PomodoroStorageKeys.liveActivityVisible)

        let state = PomodoroSharedState.current(at: now, defaults: defaults)

        XCTAssertTrue(state.isRunning)
        XCTAssertTrue(state.shouldShowLiveActivity)
        XCTAssertEqual(state.remainingSeconds, 300)
    }

    func testAllDayPlanDoesNotInventAnEstimatedDuration() {
        let item = CheckInItem(
            title: "全天整理资料",
            category: .study,
            scheduledStart: Date(timeIntervalSince1970: 4_000_000),
            plannedMinutes: 25,
            scheduleKind: .allDay
        )

        XCTAssertFalse(item.hasPlannedDuration)
        XCTAssertNil(item.plannedDurationMinutes)
        XCTAssertEqual(item.timingSummaryText, "全天")
    }

    func testEstimatedDurationCanBeExplicitlyEnabledForAnyScheduleKind() {
        let item = CheckInItem(
            title: "全天论文修改",
            category: .work,
            scheduledStart: Date(timeIntervalSince1970: 4_100_000),
            plannedMinutes: 90,
            scheduleKind: .allDay,
            plannedDurationEnabled: true
        )

        XCTAssertTrue(item.hasPlannedDuration)
        XCTAssertEqual(item.plannedDurationMinutes, 90)
        XCTAssertEqual(item.timingSummaryText, "全天 · 预计 1 小时 30 分钟")
    }

    func testCompletedLogContributesActualTimeButNotPlanCounts() throws {
        let calendar = Calendar.mojiISO
        let start = try XCTUnwrap(
            calendar.date(
                from: DateComponents(year: 2026, month: 7, day: 28, hour: 10)
            )
        )
        let completedLog = CheckInItem(
            title: "临时会议",
            category: .work,
            kind: .completedLog,
            scheduledStart: start,
            plannedMinutes: 40,
            status: .completed,
            actualStartDate: start,
            actualEndDate: start.addingTimeInterval(40 * 60),
            completedAt: start.addingTimeInterval(40 * 60),
            plannedDurationEnabled: false
        )
        let record = TimeRecord(
            title: completedLog.title,
            category: completedLog.category,
            startDate: start,
            endDate: start.addingTimeInterval(40 * 60),
            checkInItemID: completedLog.id
        )

        let summary = CheckInAnalytics.day(
            items: [completedLog],
            records: [record],
            containing: start,
            calendar: calendar
        )

        XCTAssertEqual(summary.plannedCount, 0)
        XCTAssertEqual(summary.completedCount, 0)
        XCTAssertEqual(summary.plannedMinutes, 0)
        XCTAssertEqual(summary.actualMinutes, 40)
    }

    func testPomodoroProgressAdvancesBetweenWholeSeconds() {
        let start = Date(timeIntervalSince1970: 5_000_000)
        let target = start.addingTimeInterval(60).timeIntervalSince1970
        let first = ContinuousTimerProgress.elapsedFraction(
            durationSeconds: 60,
            storedRemainingSeconds: 60,
            targetTimestamp: target,
            isRunning: true,
            at: start.addingTimeInterval(0.10)
        )
        let second = ContinuousTimerProgress.elapsedFraction(
            durationSeconds: 60,
            storedRemainingSeconds: 60,
            targetTimestamp: target,
            isRunning: true,
            at: start.addingTimeInterval(0.20)
        )

        XCTAssertGreaterThan(second, first)
        XCTAssertEqual(first, 0.10 / 60, accuracy: 0.000_001)
        XCTAssertEqual(second, 0.20 / 60, accuracy: 0.000_001)
    }

    func testLegacyAndCustomCategoriesKeepStringEncodingCompatibility() throws {
        let legacyData = try XCTUnwrap(#""study""#.data(using: .utf8))
        let legacyCategory = try JSONDecoder().decode(RecordCategory.self, from: legacyData)
        XCTAssertEqual(legacyCategory, .study)

        let customCategory = try XCTUnwrap(RecordCategory.custom(named: "健身"))
        let encoded = try JSONEncoder().encode(customCategory)
        XCTAssertEqual(String(data: encoded, encoding: .utf8), #""custom:健身""#)
        XCTAssertEqual(
            try JSONDecoder().decode(RecordCategory.self, from: encoded),
            customCategory
        )
    }

    func testCustomCategoryLibraryAddsDeduplicatesAndRemovesCategories() throws {
        let result = try XCTUnwrap(
            PlanCategoryLibrary.adding(name: "  健身  ", to: "[]")
        )
        XCTAssertEqual(result.category.displayName, "健身")
        XCTAssertEqual(
            PlanCategoryLibrary.customCategories(from: result.json),
            [result.category]
        )
        XCTAssertNil(PlanCategoryLibrary.adding(name: "健身", to: result.json))
        XCTAssertEqual(
            PlanCategoryLibrary.customCategories(
                from: PlanCategoryLibrary.removing(result.category, from: result.json)
            ),
            []
        )
    }

    func testWeeklySummaryCountsCustomCategoryMinutes() throws {
        let calendar = Calendar.mojiISO
        let monday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 27, hour: 18))
        )
        let customCategory = try XCTUnwrap(RecordCategory.custom(named: "健身"))
        let record = TimeRecord(
            title: "夜跑",
            category: customCategory,
            startDate: monday,
            endDate: monday.addingTimeInterval(35 * 60)
        )

        let summary = WeeklyAnalytics.summary(
            records: [record],
            containing: monday,
            calendar: calendar
        )

        XCTAssertEqual(summary.customMinutes, 35)
        XCTAssertEqual(summary.totalMinutes, 35)
        XCTAssertEqual(summary.days.first?.customMinutes, 35)
    }

    func testPlanTitleAndDetailedDescriptionRoundTripIndependently() throws {
        let plan = CheckInItem(
            title: "准备发布说明",
            category: .work,
            scheduledStart: Date(timeIntervalSince1970: 5_100_000),
            plannedMinutes: 25,
            note: "核对版本号、迁移说明与下载文件",
            scheduleKind: .allDay
        )

        let decoded = try JSONDecoder().decode(
            CheckInItem.self,
            from: JSONEncoder().encode(plan)
        )

        XCTAssertEqual(decoded.title, "准备发布说明")
        XCTAssertEqual(decoded.note, "核对版本号、迁移说明与下载文件")
    }

    func testLegacyTimeRecordDefaultsToPreciseTime() throws {
        let start = Date(timeIntervalSince1970: 5_200_000)
        let record = TimeRecord(
            title: "旧版记录",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(30 * 60)
        )
        let data = try JSONEncoder().encode(record)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object.removeValue(forKey: "scheduleKind")
        let legacyData = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(TimeRecord.self, from: legacyData)

        XCTAssertEqual(decoded.effectiveScheduleKind, .exactTime)
        XCTAssertTrue(decoded.hasPreciseTime)
        XCTAssertEqual(decoded.durationMinutes, 30)
    }

    func testSemanticCompletedRecordSurvivesNormalizationWithoutInventedMinutes() {
        let date = Date(timeIntervalSince1970: 5_300_000)
        let record = TimeRecord(
            title: "上午阅读",
            category: .study,
            startDate: date,
            endDate: date,
            scheduleKind: .morning
        )
        var snapshot = PlanSnapshot(schemaVersion: 9, records: [record])

        snapshot.normalize()

        XCTAssertEqual(snapshot.records, [record])
        XCTAssertEqual(snapshot.records.first?.timingSummaryText, "上午")
        XCTAssertEqual(snapshot.records.first?.durationMinutes, 0)
        XCTAssertEqual(snapshot.schemaVersion, PlanSnapshot.currentSchemaVersion)
    }

    func testCalendarCombinesPlanAndLinkedActualRecordWithoutDuplication() throws {
        let calendar = Calendar.mojiISO
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 28, hour: 9))
        )
        let tomorrow = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: today))
        let completedPlan = CheckInItem(
            title: "完成提案",
            category: .work,
            scheduledStart: today,
            plannedMinutes: 30,
            status: .completed,
            completedAt: today,
            scheduleKind: .allDay,
            plannedDurationEnabled: false
        )
        let actual = TimeRecord(
            title: completedPlan.title,
            category: completedPlan.category,
            startDate: today.addingTimeInterval(5 * 60 * 60),
            endDate: today.addingTimeInterval(5 * 60 * 60),
            checkInItemID: completedPlan.id,
            scheduleKind: .afternoon
        )
        let futurePlan = CheckInItem(
            title: "明日复习",
            category: .study,
            scheduledStart: tomorrow,
            plannedMinutes: 25,
            scheduleKind: .morning,
            plannedDurationEnabled: false
        )

        let data = PlanCalendarData(
            items: [completedPlan, futurePlan],
            records: [actual]
        )

        XCTAssertEqual(data.entries.count, 2)
        let todayEntries = data.entries(on: today, calendar: calendar)
        XCTAssertEqual(todayEntries.count, 1)
        XCTAssertEqual(todayEntries.first?.title, completedPlan.title)
        XCTAssertEqual(todayEntries.first?.timingText, "下午")
        XCTAssertEqual(todayEntries.first?.status, .completed)
        XCTAssertEqual(todayEntries.first?.actualRecord?.id, actual.id)
        XCTAssertEqual(data.entries(on: tomorrow, calendar: calendar).first?.status, .planned)
    }

    func testDeletingLinkedPomodoroRecordRestoresPlanAndRemovesGeneratedSuccessor() throws {
        let calendar = Calendar.mojiISO
        let start = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 28, hour: 9))
        )
        let planID = UUID()
        let completedPlan = CheckInItem(
            id: planID,
            title: "专注写作",
            category: .work,
            scheduledStart: start,
            plannedMinutes: 25,
            status: .completed,
            actualStartDate: start,
            actualEndDate: start.addingTimeInterval(25 * 60),
            completedAt: start.addingTimeInterval(25 * 60),
            repeatRule: .daily,
            seriesID: planID
        )
        let successor = try XCTUnwrap(
            completedPlan.recurringSuccessor(calendar: calendar)
        )
        let record = TimeRecord(
            title: completedPlan.title,
            category: completedPlan.category,
            startDate: start,
            endDate: start.addingTimeInterval(25 * 60),
            note: "由番茄钟完成",
            checkInItemID: planID
        )
        var records = [record]
        var items = [completedPlan, successor]

        TimeRecordDeletionEngine.remove(
            recordID: record.id,
            from: &records,
            checkInItems: &items
        )

        XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(items.count, 1)
        let restored = try XCTUnwrap(items.first)
        XCTAssertEqual(restored.id, planID)
        XCTAssertEqual(restored.status, .planned)
        XCTAssertNil(restored.completedAt)
        XCTAssertNil(restored.actualStartDate)
        XCTAssertNil(restored.actualEndDate)
    }

    func testDeletingStandaloneActualRecordDoesNotDeletePlans() {
        let start = Date(timeIntervalSince1970: 5_400_000)
        let plan = CheckInItem(
            title: "保留计划",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 30
        )
        let record = TimeRecord(
            title: "误触番茄钟",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(60),
            note: "由番茄钟完成"
        )
        var records = [record]
        var items = [plan]

        TimeRecordDeletionEngine.remove(
            recordID: record.id,
            from: &records,
            checkInItems: &items
        )

        XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(items, [plan])
    }

    func testMonthCalendarGridAlwaysStartsOnMondayAndContainsSixWeeks() throws {
        let calendar = Calendar.mojiISO
        let july = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 15))
        )
        let dates = PlanCalendarDates.monthGrid(containing: july, calendar: calendar)

        XCTAssertEqual(dates.count, 42)
        let first = try XCTUnwrap(dates.first)
        XCTAssertEqual(PlanCalendarDates.mondayFirstCalendar(from: calendar).component(.weekday, from: first), 2)
        XCTAssertEqual(calendar.component(.month, from: first), 6)
        XCTAssertEqual(calendar.component(.day, from: first), 29)
    }

    func testLongBreakCanBeDisabledWithoutChangingCustomDuration() {
        XCTAssertEqual(
            PomodoroCyclePolicy.nextBreakPhaseRaw(
                completedFocusCount: 4,
                longBreakInterval: 4,
                longBreakEnabled: true
            ),
            PomodoroStorageKeys.longBreakPhase
        )
        XCTAssertEqual(
            PomodoroCyclePolicy.nextBreakPhaseRaw(
                completedFocusCount: 4,
                longBreakInterval: 4,
                longBreakEnabled: false
            ),
            PomodoroStorageKeys.shortBreakPhase
        )
    }

    // MARK: - v1.16 focus coordination

    func testAbandonedFocusKeepsItsMinutesAndReturnsThePlanToTheChecklist() throws {
        let start = Date(timeIntervalSince1970: 4_000_000)
        let planID = UUID()
        var records: [TimeRecord] = []
        var items = [
            CheckInItem(
                id: planID,
                title: "读论文",
                category: .study,
                scheduledStart: start,
                plannedMinutes: 45,
                status: .inProgress,
                actualStartDate: start
            )
        ]

        FocusSessionEngine.record(
            title: "读论文",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(12 * 60),
            linkedCheckInID: planID,
            completesPlan: false,
            records: &records,
            checkInItems: &items
        )

        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].durationMinutes, 12)
        XCTAssertEqual(records[0].checkInItemID, planID)
        // The work was real, but the plan was never finished.
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].status, .planned)
        XCTAssertNil(items[0].actualStartDate)
        XCTAssertNil(items[0].completedAt)
    }

    func testCompletedFocusFinishesThePlanAndGeneratesTheNextOccurrence() throws {
        let start = Date(timeIntervalSince1970: 4_000_000)
        let planID = UUID()
        var records: [TimeRecord] = []
        var items = [
            CheckInItem(
                id: planID,
                title: "晨读",
                category: .study,
                scheduledStart: start,
                plannedMinutes: 25,
                status: .inProgress,
                actualStartDate: start,
                repeatRule: .daily,
                seriesID: planID
            )
        ]

        FocusSessionEngine.record(
            title: "晨读",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(25 * 60),
            linkedCheckInID: planID,
            completesPlan: true,
            records: &records,
            checkInItems: &items
        )

        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].durationMinutes, 25)
        let completed = try XCTUnwrap(items.first { $0.id == planID })
        XCTAssertEqual(completed.status, .completed)
        XCTAssertNotNil(completed.completedAt)
        // A repeating plan still rolls forward exactly once.
        XCTAssertEqual(items.count, 2)
        let successor = try XCTUnwrap(items.first { $0.id != planID })
        XCTAssertEqual(successor.status, .planned)
        XCTAssertEqual(successor.recurrenceSeriesID, planID)
    }

    func testUnlinkedFocusStillRecordsTimeWithoutTouchingAnyPlan() {
        let start = Date(timeIntervalSince1970: 4_000_000)
        var records: [TimeRecord] = []
        var items = [
            CheckInItem(
                title: "无关计划",
                category: .work,
                scheduledStart: start,
                plannedMinutes: 30
            )
        ]

        FocusSessionEngine.record(
            title: "   ",
            category: .work,
            startDate: start,
            endDate: start.addingTimeInterval(600),
            linkedCheckInID: nil,
            completesPlan: true,
            records: &records,
            checkInItems: &items
        )

        XCTAssertEqual(records.count, 1)
        // A blank title falls back rather than storing an empty record.
        XCTAssertEqual(records[0].title, "番茄专注")
        XCTAssertNil(records[0].checkInItemID)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].status, .planned)
    }

    func testElapsedFocusSecondsIncludesTheSegmentStillRunning() throws {
        let suiteName = "com.raydon.moji.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let now = Date(timeIntervalSince1970: 5_000_000)
        defaults.set(300, forKey: PomodoroStorageKeys.accumulated)
        defaults.set(true, forKey: PomodoroStorageKeys.running)
        defaults.set(
            now.addingTimeInterval(-120).timeIntervalSince1970,
            forKey: PomodoroStorageKeys.segmentStart
        )

        XCTAssertEqual(
            PomodoroSharedState.elapsedFocusSeconds(at: now, defaults: defaults),
            420
        )

        // Paused, only the banked segments count.
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        XCTAssertEqual(
            PomodoroSharedState.elapsedFocusSeconds(at: now, defaults: defaults),
            300
        )
    }

    func testResetClearsPlanOwnedDurationSoSettingsTakeOverAgain() throws {
        let suiteName = "com.raydon.moji.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(50, forKey: PomodoroStorageKeys.focusMinutes)
        // A 90 分钟 plan had taken over the phase length.
        defaults.set(true, forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned)
        defaults.set(90 * 60, forKey: PomodoroStorageKeys.phaseDuration)

        let state = PomodoroSharedState.reset(defaults: defaults)

        XCTAssertFalse(defaults.bool(forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned))
        XCTAssertEqual(state.remainingSeconds, 50 * 60)
        XCTAssertEqual(defaults.integer(forKey: PomodoroStorageKeys.phaseDuration), 50 * 60)
    }

    // MARK: - v2.0.1 anniversary elapsed time

    func testRepeatingAnniversaryReportsBothNextOccurrenceAndElapsedTime() throws {
        let calendar = Calendar.current
        let origin = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2020, month: 5, day: 20))
        )
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let event = CountdownEvent(
            title: "母亲生日",
            targetDate: origin,
            repeatRule: .yearly
        )

        // Still counts down to the next birthday...
        let next = event.occurrenceDate(relativeTo: now, calendar: calendar)
        let count = CountdownDayCalculator.count(to: next, from: now, calendar: calendar)
        XCTAssertFalse(count.isPast)
        XCTAssertEqual(calendar.component(.year, from: next), 2027)
        XCTAssertEqual(calendar.component(.month, from: next), 5)

        // ...and still reports how long it has been since the date itself.
        XCTAssertEqual(event.elapsedDays(relativeTo: now, calendar: calendar), 2261)
        let summary = try XCTUnwrap(
            event.elapsedSummaryText(relativeTo: now, calendar: calendar)
        )
        XCTAssertTrue(summary.hasPrefix("已经 6 年"), summary)
        XCTAssertTrue(summary.contains("共 2261 天"), summary)
    }

    func testFutureDateHasNoElapsedTime() throws {
        let calendar = Calendar.current
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let future = try XCTUnwrap(calendar.date(byAdding: .day, value: 30, to: now))
        let event = CountdownEvent(title: "远行", targetDate: future)

        XCTAssertNil(event.elapsedDays(relativeTo: now, calendar: calendar))
        XCTAssertNil(event.elapsedSummaryText(relativeTo: now, calendar: calendar))
    }

    func testElapsedSummaryUsesDaysWithinTheFirstMonth() throws {
        let calendar = Calendar.current
        let now = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let recent = try XCTUnwrap(calendar.date(byAdding: .day, value: -9, to: now))
        let event = CountdownEvent(title: "搬家", targetDate: recent)

        XCTAssertEqual(
            event.elapsedSummaryText(relativeTo: now, calendar: calendar),
            "已经 9 天"
        )
    }

    func testCalendarDayIndexReturnsTheSameEntriesAsAFullScan() throws {
        let calendar = Calendar.current
        let day = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29, hour: 9))
        )
        let other = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: day))
        let items = [
            CheckInItem(title: "今天甲", category: .study, scheduledStart: day, plannedMinutes: 25),
            CheckInItem(title: "今天乙", category: .work, scheduledStart: day, plannedMinutes: 30),
            CheckInItem(title: "明天", category: .study, scheduledStart: other, plannedMinutes: 15)
        ]
        let data = PlanCalendarData(items: items, records: [])

        // The bucketed lookup must agree with the linear filter it replaced.
        let indexed = data.entries(on: day, calendar: calendar).map(\.title).sorted()
        let scanned = data.entries
            .filter { calendar.isDate($0.date, inSameDayAs: day) }
            .map(\.title)
            .sorted()
        XCTAssertEqual(indexed, scanned)
        XCTAssertEqual(indexed, ["今天乙", "今天甲"].sorted())
        XCTAssertEqual(data.entries(on: other, calendar: calendar).count, 1)
    }

    func testCalendarIncludesOneTimeAndRecurringCountdownsOnTheirOccurrences() throws {
        let calendar = Calendar.current
        let day = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let nextDay = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: day))
        let events = [
            CountdownEvent(title: "一次交付", targetDate: day),
            CountdownEvent(
                title: "周年",
                targetDate: try XCTUnwrap(
                    calendar.date(from: DateComponents(year: 2020, month: 7, day: 29))
                ),
                repeatRule: .yearly
            ),
            CountdownEvent(
                title: "月度复盘",
                targetDate: try XCTUnwrap(
                    calendar.date(from: DateComponents(year: 2026, month: 6, day: 29))
                ),
                repeatRule: .monthly
            ),
            CountdownEvent(
                title: "周会",
                targetDate: try XCTUnwrap(
                    calendar.date(from: DateComponents(year: 2026, month: 7, day: 22))
                ),
                repeatRule: .weekly
            ),
            CountdownEvent(
                title: "尚未开始的月计划",
                targetDate: try XCTUnwrap(
                    calendar.date(from: DateComponents(year: 2026, month: 8, day: 29))
                ),
                repeatRule: .monthly
            )
        ]
        let data = PlanCalendarData(items: [], records: [], countdowns: events)

        let entries = data.entries(on: day, calendar: calendar)
        XCTAssertEqual(
            Set(entries.map(\.title)),
            Set(["一次交付", "周年", "月度复盘", "周会"])
        )
        XCTAssertEqual(Set(entries.map(\.id)).count, entries.count)
        XCTAssertTrue(entries.allSatisfy(\.isCountdown))
        XCTAssertFalse(
            data.entries(on: nextDay, calendar: calendar)
                .contains { $0.title == "一次交付" }
        )
    }

    func testCountdownOrderingSkipsExpiredOneTimeEventsForWidgets() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let expired = CountdownEvent(
            title: "已经结束",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: -2, to: today)),
            sortOrder: 0
        )
        let upcoming = CountdownEvent(
            title: "下一项",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 2, to: today)),
            sortOrder: 1
        )

        XCTAssertEqual(
            CountdownOrdering.next(
                [expired, upcoming],
                relativeTo: today,
                calendar: calendar
            )?.id,
            upcoming.id
        )
    }

    /// Ending a focus from the watch cannot ask the user anything, so a plan it
    /// had claimed must never be left sitting at 进行中 — including in the first
    /// minute, where no record is written at all.
    func testReleasingLinkedPlanReturnsAnInProgressPlanToTheChecklist() throws {
        let suiteName = "com.raydon.moji.release-tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            SharedPersistence.mutate { $0.checkInItems.removeAll() }
        }

        let plan = CheckInItem(
            title: "写周报",
            category: .work,
            scheduledStart: Date(),
            plannedMinutes: 25,
            status: .inProgress,
            actualStartDate: Date()
        )
        SharedPersistence.mutate { snapshot in
            snapshot.checkInItems.removeAll()
            snapshot.checkInItems.append(plan)
        }
        defaults.set(plan.id.uuidString, forKey: PomodoroStorageKeys.linkedPlanID)

        PomodoroSharedState.releaseLinkedPlanIfNeeded(defaults: defaults)

        let stored = SharedPersistence.load().checkInItems.first { $0.id == plan.id }
        XCTAssertEqual(stored?.status, .planned)
        XCTAssertNil(stored?.actualStartDate)
        XCTAssertEqual(defaults.string(forKey: PomodoroStorageKeys.linkedPlanID), "")
    }

    func testCountdownDateSortPutsNearestUpcomingFirst() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let far = CountdownEvent(
            title: "远期",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 40, to: today)),
            sortOrder: 0
        )
        let near = CountdownEvent(
            title: "近期",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 3, to: today)),
            sortOrder: 1
        )

        let sorted = CountdownOrdering.sorted(
            [far, near],
            mode: .date,
            relativeTo: today,
            calendar: calendar
        )

        XCTAssertEqual(sorted.map(\.id), [near.id, far.id])
    }

    /// The 纪念日 page counts up, so "nearest to today" means the most recent
    /// date rather than the smallest number.
    func testCountdownDateSortPutsMostRecentPastFirst() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let longAgo = CountdownEvent(
            title: "很久以前",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: -400, to: today)),
            sortOrder: 0
        )
        let recent = CountdownEvent(
            title: "最近",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: -5, to: today)),
            sortOrder: 1
        )

        let sorted = CountdownOrdering.sorted(
            [longAgo, recent],
            mode: .date,
            relativeTo: today,
            calendar: calendar
        )

        XCTAssertEqual(sorted.map(\.id), [recent.id, longAgo.id])
    }

    func testCountdownDateSortKeepsPinnedEventFirst() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let pinnedFarAway = CountdownEvent(
            title: "重点但很远",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 90, to: today)),
            isPinned: true,
            sortOrder: 1
        )
        let soon = CountdownEvent(
            title: "很快",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: today)),
            sortOrder: 0
        )

        XCTAssertEqual(
            CountdownOrdering.sorted(
                [soon, pinnedFarAway],
                mode: .date,
                relativeTo: today,
                calendar: calendar
            ).map(\.id),
            [pinnedFarAway.id, soon.id]
        )
        // Manual order stays exactly as dragged, otherwise a marked row could
        // never be moved down the list.
        XCTAssertEqual(
            CountdownOrdering.sorted(
                [soon, pinnedFarAway],
                mode: .manual,
                relativeTo: today,
                calendar: calendar
            ).map(\.id),
            [soon.id, pinnedFarAway.id]
        )
    }

    func testWidgetCountdownPrefersPinnedEventOverNearerOne() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29))
        )
        let nearer = CountdownEvent(
            title: "更近",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 2, to: today)),
            sortOrder: 0
        )
        let pinned = CountdownEvent(
            title: "重点",
            targetDate: try XCTUnwrap(calendar.date(byAdding: .day, value: 20, to: today)),
            isPinned: true,
            sortOrder: 1
        )

        XCTAssertEqual(
            CountdownOrdering.next(
                [nearer, pinned],
                relativeTo: today,
                calendar: calendar
            )?.id,
            pinned.id
        )
    }

    @MainActor
    func testAppPomodoroResetReleasesPlanOwnedDuration() throws {
        let suiteName = "com.raydon.moji.engine-tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(50, forKey: PomodoroStorageKeys.focusMinutes)
        defaults.set(90 * 60, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(90 * 60, forKey: PomodoroStorageKeys.remaining)
        defaults.set(true, forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned)

        let engine = PomodoroEngine(
            store: PlanStore(snapshot: .empty),
            defaults: defaults
        )
        engine.reset(at: Date(timeIntervalSince1970: 7_000_000))

        XCTAssertFalse(defaults.bool(forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned))
        XCTAssertEqual(engine.phaseDurationSeconds, 50 * 60)
        XCTAssertEqual(engine.remainingSeconds(), 50 * 60)
    }

    @MainActor
    func testAppPomodoroCanSwitchDirectlyFromRunningBreakToFocus() throws {
        let suiteName = "com.raydon.moji.phase-switch-tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let start = Date()
        defaults.set(PomodoroStorageKeys.shortBreakPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(5, forKey: PomodoroStorageKeys.shortBreakMinutes)
        defaults.set(25, forKey: PomodoroStorageKeys.focusMinutes)
        defaults.set(5 * 60, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(5 * 60, forKey: PomodoroStorageKeys.remaining)
        defaults.set(start.timeIntervalSince1970, forKey: PomodoroStorageKeys.segmentStart)
        defaults.set(
            start.addingTimeInterval(5 * 60).timeIntervalSince1970,
            forKey: PomodoroStorageKeys.target
        )
        defaults.set(true, forKey: PomodoroStorageKeys.running)
        defaults.set(true, forKey: PomodoroStorageKeys.liveActivityVisible)

        let engine = PomodoroEngine(
            store: PlanStore(snapshot: .empty),
            defaults: defaults
        )
        engine.selectPhase(
            .focus,
            keepCurrentRecord: false,
            at: start.addingTimeInterval(30)
        )

        XCTAssertEqual(engine.phase, .focus)
        XCTAssertFalse(engine.isRunning)
        XCTAssertFalse(engine.hasActiveSession)
        XCTAssertEqual(engine.remainingSeconds(), 25 * 60)
        XCTAssertEqual(defaults.double(forKey: PomodoroStorageKeys.target), 0)
        XCTAssertEqual(defaults.integer(forKey: PomodoroStorageKeys.accumulated), 0)
    }

    @MainActor
    func testAppPomodoroDiscardTerminationClearsPausedSession() throws {
        let suiteName = "com.raydon.moji.termination-tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(25, forKey: PomodoroStorageKeys.focusMinutes)
        defaults.set(25 * 60, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(20 * 60, forKey: PomodoroStorageKeys.remaining)
        defaults.set(5 * 60, forKey: PomodoroStorageKeys.accumulated)
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        defaults.set(true, forKey: PomodoroStorageKeys.liveActivityVisible)

        let engine = PomodoroEngine(
            store: PlanStore(snapshot: .empty),
            defaults: defaults
        )
        XCTAssertTrue(engine.hasActiveSession)
        XCTAssertTrue(engine.canKeepCurrentRecord())

        engine.terminate(keepRecord: false)

        XCTAssertFalse(engine.isRunning)
        XCTAssertFalse(engine.hasActiveSession)
        XCTAssertFalse(engine.canKeepCurrentRecord())
        XCTAssertEqual(engine.remainingSeconds(), 25 * 60)
        XCTAssertEqual(defaults.integer(forKey: PomodoroStorageKeys.accumulated), 0)
        XCTAssertFalse(defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible))
    }

    func testQuickPlanWidgetAddsUniqueTodayPlansWithExpectedCategories() throws {
        let calendar = Calendar.current
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29, hour: 15))
        )
        let tomorrow = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: today))
        var snapshot = PlanSnapshot(
            checkInItems: [
                CheckInItem(
                    title: "新计划",
                    category: .study,
                    scheduledStart: tomorrow,
                    plannedMinutes: 25,
                    scheduleKind: .allDay
                )
            ]
        )

        let first = QuickPlanMutationEngine.append(
            kind: .general,
            at: today,
            plannedMinutes: 30,
            defaultCategory: .work,
            calendar: calendar,
            to: &snapshot
        )
        let second = QuickPlanMutationEngine.append(
            kind: .general,
            at: today,
            plannedMinutes: 30,
            defaultCategory: .work,
            calendar: calendar,
            to: &snapshot
        )
        let study = QuickPlanMutationEngine.append(
            kind: .study,
            at: today,
            plannedMinutes: 25,
            defaultCategory: .work,
            calendar: calendar,
            to: &snapshot
        )

        XCTAssertEqual(first.title, "新计划")
        XCTAssertEqual(second.title, "新计划 2")
        XCTAssertEqual(study.title, "学习计划")
        XCTAssertEqual(first.category, .work)
        XCTAssertEqual(study.category, .study)
        XCTAssertEqual(first.effectiveScheduleKind, .allDay)
        XCTAssertFalse(first.hasConfiguredDetails)
        XCTAssertFalse(first.hasPlannedDuration)
        XCTAssertTrue(calendar.isDate(first.scheduledStart, inSameDayAs: today))
    }

    func testQuickPlanPresetJSONRoundTripsCustomLabelsAndCategory() throws {
        let customCategory = try XCTUnwrap(RecordCategory.custom(named: "写作"))
        let presets = [
            QuickPlanPreset(
                id: "morning",
                buttonTitle: "晨读",
                planTitle: "阅读二十分钟",
                category: .study
            ),
            QuickPlanPreset(
                id: "draft",
                buttonTitle: "写作",
                planTitle: "继续写作",
                category: customCategory
            ),
            QuickPlanPreset(
                id: "inbox",
                buttonTitle: "收集",
                planTitle: "整理待办",
                category: nil
            )
        ]

        let decoded = QuickPlanPresetStore.presets(
            from: QuickPlanPresetStore.json(for: presets)
        )

        XCTAssertEqual(decoded, presets)
        XCTAssertEqual(decoded[1].category?.displayName, "写作")
        XCTAssertEqual(decoded.count, QuickPlanPresetStore.maximumCount)
    }

    func testQuickPlanWidgetUsesCustomizedPresetForPlanAndDuplicateTitle() throws {
        let calendar = Calendar.mojiISO
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 29, hour: 15))
        )
        let category = try XCTUnwrap(RecordCategory.custom(named: "健身"))
        let preset = QuickPlanPreset(
            id: "fitness",
            buttonTitle: "训练",
            planTitle: "晚间训练",
            category: category
        )
        var snapshot = PlanSnapshot.empty

        let first = QuickPlanMutationEngine.append(
            preset: preset,
            at: today,
            plannedMinutes: 40,
            defaultCategory: .work,
            calendar: calendar,
            to: &snapshot
        )
        let second = QuickPlanMutationEngine.append(
            preset: preset,
            at: today,
            plannedMinutes: 40,
            defaultCategory: .work,
            calendar: calendar,
            to: &snapshot
        )

        XCTAssertEqual(first.title, "晚间训练")
        XCTAssertEqual(second.title, "晚间训练 2")
        XCTAssertEqual(first.category, category)
        XCTAssertEqual(first.effectiveScheduleKind, .allDay)
    }

    func testQuickPlanWidgetCompletesActivePlanAndRestoresItDirectly() {
        let startedAt = Date(timeIntervalSince1970: 8_000_000)
        let completedAt = startedAt.addingTimeInterval(18 * 60)
        let planID = UUID()
        let plan = CheckInItem(
            id: planID,
            title: "今日专注",
            category: .study,
            scheduledStart: startedAt,
            plannedMinutes: 25,
            status: .inProgress,
            actualStartDate: startedAt,
            repeatRule: .daily,
            seriesID: planID
        )
        var snapshot = PlanSnapshot(
            checkInItems: [plan],
            activeSession: ActiveSession(
                title: plan.title,
                category: plan.category,
                startedAt: startedAt,
                checkInItemID: planID,
                plannedDurationMinutes: 25
            )
        )

        let completedResult = QuickPlanMutationEngine.toggle(
            planID: planID,
            at: completedAt,
            in: &snapshot
        )

        XCTAssertNil(snapshot.activeSession)
        XCTAssertEqual(snapshot.records.count, 1)
        XCTAssertEqual(snapshot.records[0].durationMinutes, 18)
        XCTAssertEqual(snapshot.checkInItems.count, 2)
        XCTAssertEqual(
            snapshot.checkInItems.first(where: { $0.id == planID })?.status,
            .completed
        )
        XCTAssertNotNil(completedResult.notificationIdentifierToRemove)
        XCTAssertNotNil(completedResult.notificationItemToSchedule)
        let successorNotificationIdentifier =
            completedResult.notificationItemToSchedule?.notificationIdentifier

        let restoredResult = QuickPlanMutationEngine.toggle(
            planID: planID,
            at: completedAt.addingTimeInterval(30),
            in: &snapshot
        )

        XCTAssertTrue(snapshot.records.isEmpty)
        XCTAssertEqual(snapshot.checkInItems.count, 1)
        XCTAssertEqual(snapshot.checkInItems[0].status, .planned)
        XCTAssertNil(snapshot.checkInItems[0].completedAt)
        XCTAssertNil(snapshot.checkInItems[0].actualStartDate)
        XCTAssertNil(snapshot.checkInItems[0].actualEndDate)
        XCTAssertEqual(
            restoredResult.notificationIdentifierToRemove,
            successorNotificationIdentifier
        )
        XCTAssertEqual(restoredResult.notificationItemToSchedule?.id, planID)
    }

    func testUndoCompletionKeepsEarlierPartialFocusRecords() {
        let start = Date(timeIntervalSince1970: 9_000_000)
        let planID = UUID()
        let partial = TimeRecord(
            title: "分段一",
            category: .study,
            startDate: start,
            endDate: start.addingTimeInterval(10 * 60),
            checkInItemID: planID
        )
        let completionStart = start.addingTimeInterval(20 * 60)
        let completionEnd = completionStart.addingTimeInterval(25 * 60)
        let completion = TimeRecord(
            title: "分段二",
            category: .study,
            startDate: completionStart,
            endDate: completionEnd,
            checkInItemID: planID
        )
        let plan = CheckInItem(
            id: planID,
            title: "分段完成计划",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 35,
            status: .completed,
            actualStartDate: completionStart,
            actualEndDate: completionEnd,
            completedAt: completionEnd
        )
        var records = [partial, completion]

        PlanCompletionReversion.removeCompletionRecord(for: plan, from: &records)

        XCTAssertEqual(records.map(\.id), [partial.id])
    }

    func testCalendarKeepsPartialRecordsLinkedToACompletedPlan() {
        let start = Date(timeIntervalSince1970: 9_100_000)
        let planID = UUID()
        let partial = TimeRecord(
            title: "早先专注",
            category: .work,
            startDate: start,
            endDate: start.addingTimeInterval(12 * 60),
            checkInItemID: planID
        )
        let completionStart = start.addingTimeInterval(30 * 60)
        let completionEnd = completionStart.addingTimeInterval(20 * 60)
        let completion = TimeRecord(
            title: "完成专注",
            category: .work,
            startDate: completionStart,
            endDate: completionEnd,
            checkInItemID: planID
        )
        let plan = CheckInItem(
            id: planID,
            title: "报告",
            category: .work,
            scheduledStart: start,
            plannedMinutes: 32,
            status: .completed,
            actualStartDate: completionStart,
            actualEndDate: completionEnd,
            completedAt: completionEnd
        )

        let data = PlanCalendarData(
            items: [plan],
            records: [partial, completion]
        )

        XCTAssertEqual(data.entries.count, 2)
        XCTAssertEqual(
            data.entries.compactMap(\.actualRecord).map(\.id).sorted { $0.uuidString < $1.uuidString },
            [partial.id, completion.id].sorted { $0.uuidString < $1.uuidString }
        )
    }

    func testCountdownReorderRejectsDuplicateIdentifiersWithoutCrashing() {
        let first = CountdownEvent(
            title: "原始",
            targetDate: Date(timeIntervalSince1970: 9_200_000),
            sortOrder: 0
        )
        var duplicate = first
        duplicate.title = "重复标识"

        let result = CountdownOrdering.applyingVisibleOrder(
            [first.id],
            to: [first, duplicate]
        )

        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(Set(result.map(\.id)), [first.id])
        XCTAssertEqual(Set(result.map(\.title)), ["原始", "重复标识"])
    }

    func testPausedPomodoroStillClaimsTheFocusSession() throws {
        let suiteName = "com.raydon.moji.claim-tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(25 * 60, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(20 * 60, forKey: PomodoroStorageKeys.remaining)
        defaults.set(false, forKey: PomodoroStorageKeys.running)

        XCTAssertTrue(PomodoroSharedState.hasActiveFocusClaim(defaults: defaults))

        defaults.set(25 * 60, forKey: PomodoroStorageKeys.remaining)
        XCTAssertFalse(PomodoroSharedState.hasActiveFocusClaim(defaults: defaults))
    }

    func testUnsupportedBackupDoesNotRestorePreferencesBeforeValidation() throws {
        let originalBackup = try SharedPersistence.exportBackup()
        let defaults = SharedPersistence.sharedDefaults
        let originalFocus = defaults.object(forKey: PomodoroStorageKeys.focusMinutes)
        defer {
            _ = try? SharedPersistence.importBackup(originalBackup)
            if let originalFocus {
                defaults.set(originalFocus, forKey: PomodoroStorageKeys.focusMinutes)
            } else {
                defaults.removeObject(forKey: PomodoroStorageKeys.focusMinutes)
            }
        }

        defaults.set(41, forKey: PomodoroStorageKeys.focusMinutes)
        let candidate = try SharedPersistence.exportBackup()
        var archive = try XCTUnwrap(
            JSONSerialization.jsonObject(with: candidate) as? [String: Any]
        )
        var snapshot = try XCTUnwrap(archive["snapshot"] as? [String: Any])
        snapshot["schemaVersion"] = PlanSnapshot.currentSchemaVersion + 1
        archive["snapshot"] = snapshot
        var preferences = try XCTUnwrap(archive["preferences"] as? [String: Any])
        preferences["focusMinutes"] = 99
        archive["preferences"] = preferences
        let unsupported = try JSONSerialization.data(withJSONObject: archive)

        XCTAssertThrowsError(try SharedPersistence.importBackup(unsupported))
        XCTAssertEqual(defaults.integer(forKey: PomodoroStorageKeys.focusMinutes), 41)
    }

    func testMutationNeverDowngradesAFutureSnapshot() throws {
        let originalBackup = try SharedPersistence.exportBackup()
        defer { _ = try? SharedPersistence.importBackup(originalBackup) }

        var future = PlanSnapshot(
            schemaVersion: PlanSnapshot.currentSchemaVersion + 1,
            checkInItems: [
                CheckInItem(
                    title: "未来版本数据",
                    category: .study,
                    scheduledStart: Date(),
                    plannedMinutes: 25
                )
            ],
            lastUpdated: Date().addingTimeInterval(24 * 60 * 60)
        )
        future.schemaVersion = PlanSnapshot.currentSchemaVersion + 1
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        SharedPersistence.sharedDefaults.set(
            try encoder.encode(future),
            forKey: SharedPersistence.snapshotKey
        )

        let result = SharedPersistence.mutate { snapshot in
            snapshot.checkInItems.removeAll()
        }

        XCTAssertEqual(result.schemaVersion, PlanSnapshot.currentSchemaVersion + 1)
        XCTAssertEqual(result.checkInItems.first?.title, "未来版本数据")
    }

    func testMemoRoundTripsWithoutPlanSemantics() throws {
        let createdAt = Date(timeIntervalSince1970: 9_300_000)
        let memo = MemoItem(
            title: "书单",
            content: "只记下来，不需要完成或计时。",
            isPinned: true,
            createdAt: createdAt,
            updatedAt: createdAt.addingTimeInterval(60)
        )

        let decoded = try JSONDecoder().decode(
            MemoItem.self,
            from: JSONEncoder().encode(memo)
        )

        XCTAssertEqual(decoded, memo)
        XCTAssertEqual(decoded.displayTitle, "书单")
    }

    func testUntitledMemoUsesFirstContentLineAsDisplayTitle() {
        let memo = MemoItem(content: "  重要想法  \n后续说明")

        XCTAssertEqual(memo.displayTitle, "重要想法")
    }

    func testChecklistMemoRoundTripsAndUsesFirstItemAsFallbackTitle() throws {
        let memo = MemoItem(
            mode: .checklist,
            checklistItems: [
                MemoChecklistItem(text: "确认车票", isCompleted: true),
                MemoChecklistItem(text: "带上充电器")
            ]
        )

        let decoded = try JSONDecoder().decode(
            MemoItem.self,
            from: JSONEncoder().encode(memo)
        )

        XCTAssertEqual(decoded, memo)
        XCTAssertEqual(decoded.displayTitle, "确认车票")
        XCTAssertTrue(decoded.checklistItems[0].isCompleted)
    }

    func testLegacyMemoWithoutChecklistFieldsMigratesAsPlainNote() throws {
        struct LegacyMemo: Encodable {
            let id: UUID
            let title: String
            let content: String
            let isPinned: Bool
            let createdAt: Date
            let updatedAt: Date
        }

        let date = Date(timeIntervalSince1970: 9_350_000)
        let data = try JSONEncoder().encode(
            LegacyMemo(
                id: UUID(),
                title: "旧备忘",
                content: "保留原文",
                isPinned: false,
                createdAt: date,
                updatedAt: date
            )
        )

        let decoded = try JSONDecoder().decode(MemoItem.self, from: data)

        XCTAssertEqual(decoded.mode, .note)
        XCTAssertTrue(decoded.checklistItems.isEmpty)
        XCTAssertEqual(decoded.content, "保留原文")
    }

    func testChecklistReturnAtEndInsertsNextItem() {
        let first = MemoChecklistItem(text: "确认车票")
        var items = [first]

        let outcome = MemoChecklistEditingEngine.handleReturn(
            items: &items,
            itemID: first.id,
            selection: NSRange(location: 4, length: 0)
        )

        XCTAssertEqual(items.map(\.text), ["确认车票", ""])
        XCTAssertEqual(
            outcome,
            .focus(MemoChecklistCaret(itemID: items[1].id, utf16Offset: 0))
        )
    }

    func testChecklistReturnAtCaretSplitsCurrentItem() {
        let first = MemoChecklistItem(text: "购买苹果和梨")
        var items = [first]

        let outcome = MemoChecklistEditingEngine.handleReturn(
            items: &items,
            itemID: first.id,
            selection: NSRange(location: 4, length: 0)
        )

        XCTAssertEqual(items.map(\.text), ["购买苹果", "和梨"])
        XCTAssertEqual(
            outcome,
            .focus(MemoChecklistCaret(itemID: items[1].id, utf16Offset: 0))
        )
    }

    func testChecklistReturnOnBlankEndsListWithoutAddingBlankRow() {
        let blank = MemoChecklistItem()
        var items = [blank]

        let outcome = MemoChecklistEditingEngine.handleReturn(
            items: &items,
            itemID: blank.id,
            selection: NSRange(location: 0, length: 0)
        )

        XCTAssertEqual(outcome, .endEditing)
        XCTAssertEqual(items, [blank])
    }

    func testChecklistBackspaceAtStartMergesWithPreviousItem() {
        let previous = MemoChecklistItem(text: "带上📚")
        let current = MemoChecklistItem(text: "和充电器")
        var items = [previous, current]

        let outcome = MemoChecklistEditingEngine.handleBackspaceAtStart(
            items: &items,
            itemID: current.id
        )

        XCTAssertEqual(items.map(\.text), ["带上📚和充电器"])
        XCTAssertEqual(
            outcome,
            .handled(
                MemoChecklistCaret(
                    itemID: previous.id,
                    utf16Offset: ("带上📚" as NSString).length
                )
            )
        )
    }

    func testChecklistBackspaceAtStartOfFirstNonemptyItemUsesSystemBehavior() {
        let first = MemoChecklistItem(text: "第一项")
        var items = [first]

        let outcome = MemoChecklistEditingEngine.handleBackspaceAtStart(
            items: &items,
            itemID: first.id
        )

        XCTAssertEqual(outcome, .system)
        XCTAssertEqual(items, [first])
    }

    func testChecklistDeletingOnlyRowKeepsOneEditableBlank() {
        let only = MemoChecklistItem(text: "稍后删除")
        var items = [only]

        let caret = MemoChecklistEditingEngine.removeItem(
            items: &items,
            itemID: only.id
        )

        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].text, "")
        XCTAssertEqual(
            caret,
            MemoChecklistCaret(itemID: items[0].id, utf16Offset: 0)
        )
    }

    func testSnapshotNormalizesPinnedMemosBeforeRecentMemos() {
        let older = Date(timeIntervalSince1970: 9_400_000)
        let recent = MemoItem(
            title: "最近",
            content: "普通备忘",
            updatedAt: older.addingTimeInterval(3_600)
        )
        let pinned = MemoItem(
            title: "置顶",
            content: "始终在前",
            isPinned: true,
            updatedAt: older
        )
        var snapshot = PlanSnapshot(schemaVersion: 10, memos: [recent, pinned])

        snapshot.normalize()

        XCTAssertEqual(snapshot.schemaVersion, PlanSnapshot.currentSchemaVersion)
        XCTAssertEqual(snapshot.memos.map(\.id), [pinned.id, recent.id])
    }

    // MARK: - Configurable widgets

    private func widgetCountdownFixtures(now: Date) -> [CountdownEvent] {
        [
            CountdownEvent(
                title: "远行",
                targetDate: now.addingTimeInterval(30 * 24 * 60 * 60)
            ),
            CountdownEvent(
                title: "项目交付",
                targetDate: now.addingTimeInterval(2 * 24 * 60 * 60)
            ),
            CountdownEvent(
                title: "初次相遇",
                targetDate: now.addingTimeInterval(-365 * 24 * 60 * 60)
            ),
            CountdownEvent(
                title: "搬进新家",
                targetDate: now.addingTimeInterval(-40 * 24 * 60 * 60)
            )
        ]
    }

    func testCountdownWidgetShowsNearestUpcomingWhenNothingIsSelected() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let events = widgetCountdownFixtures(now: now)

        let shown = WidgetSelectionEngine.countdowns(
            selecting: [],
            from: events,
            past: false,
            now: now
        )

        XCTAssertEqual(shown.map(\.title), ["项目交付", "远行"])
    }

    func testAnniversaryWidgetOnlyShowsDatesThatHavePassed() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let events = widgetCountdownFixtures(now: now)

        let shown = WidgetSelectionEngine.countdowns(
            selecting: [],
            from: events,
            past: true,
            now: now
        )

        XCTAssertEqual(shown.map(\.title), ["搬进新家", "初次相遇"])
    }

    func testCountdownWidgetKeepsChosenSlotOrderAndDropsDeletedDates() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let events = widgetCountdownFixtures(now: now)
        let faraway = try! XCTUnwrap(events.first { $0.title == "远行" })
        let delivery = try! XCTUnwrap(events.first { $0.title == "项目交付" })
        let deleted = UUID()

        let shown = WidgetSelectionEngine.countdowns(
            selecting: [faraway.id, deleted, delivery.id],
            from: events,
            past: false,
            now: now
        )

        // The slot order wins over the date order, and a date deleted in the
        // app must not leave a blank row behind.
        XCTAssertEqual(shown.map(\.title), ["远行", "项目交付"])
    }

    func testCountdownWidgetFallsBackWhenEverySelectedDateIsGone() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let events = widgetCountdownFixtures(now: now)

        let shown = WidgetSelectionEngine.countdowns(
            selecting: [UUID(), UUID()],
            from: events,
            past: false,
            now: now
        )

        XCTAssertEqual(shown.map(\.title), ["项目交付", "远行"])
    }

    func testPlanWidgetFillsRemainingSlotsWithTodayPlans() {
        let now = Date()
        let calendar = Calendar.current
        let morning = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: now) ?? now
        let pinned = CheckInItem(
            title: "钉住的计划",
            category: .work,
            scheduledStart: calendar.date(byAdding: .day, value: 3, to: morning) ?? morning,
            plannedMinutes: 30
        )
        let first = CheckInItem(
            title: "今日一",
            category: .study,
            scheduledStart: morning,
            plannedMinutes: 25
        )
        let second = CheckInItem(
            title: "今日二",
            category: .study,
            scheduledStart: morning.addingTimeInterval(3_600),
            plannedMinutes: 25
        )
        let third = CheckInItem(
            title: "今日三",
            category: .study,
            scheduledStart: morning.addingTimeInterval(7_200),
            plannedMinutes: 25
        )
        let snapshot = PlanSnapshot(checkInItems: [pinned, first, second, third])

        let filled = WidgetSelectionEngine.plans(
            selecting: [pinned.id],
            fillsWithToday: true,
            in: snapshot,
            now: now
        )
        XCTAssertEqual(filled.map(\.title), ["钉住的计划", "今日一", "今日二"])

        let pinnedOnly = WidgetSelectionEngine.plans(
            selecting: [pinned.id],
            fillsWithToday: false,
            in: snapshot,
            now: now
        )
        XCTAssertEqual(pinnedOnly.map(\.title), ["钉住的计划"])
    }

    func testPlanWidgetNeverRepeatsAPinnedPlanInTheFilledRows() {
        let now = Date()
        let calendar = Calendar.current
        let morning = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: now) ?? now
        let today = CheckInItem(
            title: "今天就要做",
            category: .study,
            scheduledStart: morning,
            plannedMinutes: 25
        )
        let other = CheckInItem(
            title: "另一项",
            category: .study,
            scheduledStart: morning.addingTimeInterval(3_600),
            plannedMinutes: 25
        )
        let snapshot = PlanSnapshot(checkInItems: [today, other])

        let shown = WidgetSelectionEngine.plans(
            selecting: [today.id],
            fillsWithToday: true,
            in: snapshot,
            now: now
        )

        XCTAssertEqual(shown.map(\.title), ["今天就要做", "另一项"])
    }

    func testChecklistDropsPlansFinishedOnAnEarlierDay() throws {
        let calendar = Calendar.mojiISO
        let yesterday = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 5, hour: 9))
        )
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 6, hour: 9))
        )
        var finished = CheckInItem(
            title: "昨天做完的",
            category: .study,
            scheduledStart: yesterday,
            plannedMinutes: 25
        )
        finished.status = .completed
        finished.completedAt = yesterday.addingTimeInterval(3_600)
        let unfinished = CheckInItem(
            title: "昨天没做完的",
            category: .study,
            scheduledStart: yesterday,
            plannedMinutes: 25
        )

        XCTAssertFalse(
            finished.isVisibleInChecklist(
                on: today,
                carriesOverUnfinished: true,
                calendar: calendar
            )
        )
        XCTAssertTrue(
            unfinished.isVisibleInChecklist(
                on: today,
                carriesOverUnfinished: true,
                calendar: calendar
            )
        )

        let board = WidgetSelectionEngine.todayPlans(
            in: PlanSnapshot(checkInItems: [finished, unfinished]),
            now: today,
            carriesOverUnfinished: true,
            calendar: calendar
        )
        XCTAssertEqual(board.map(\.title), ["昨天没做完的"])
    }

    func testChecklistKeepsPlansFinishedTodayButNotTomorrow() throws {
        let calendar = Calendar.mojiISO
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 6, hour: 9))
        )
        var finished = CheckInItem(
            title: "今天做完的",
            category: .study,
            scheduledStart: today,
            plannedMinutes: 25
        )
        finished.status = .completed
        finished.completedAt = today.addingTimeInterval(1_800)

        XCTAssertTrue(
            finished.isVisibleInChecklist(
                on: today.addingTimeInterval(6 * 3_600),
                carriesOverUnfinished: true,
                calendar: calendar
            )
        )

        let tomorrow = try XCTUnwrap(
            calendar.date(byAdding: .day, value: 1, to: today)
        )
        let board = WidgetSelectionEngine.todayPlans(
            in: PlanSnapshot(checkInItems: [finished]),
            now: tomorrow,
            carriesOverUnfinished: true,
            calendar: calendar
        )
        XCTAssertTrue(board.isEmpty)
    }

    func testCarryOverSettingDecidesWhetherOverduePlansFollowTheUser() throws {
        let calendar = Calendar.mojiISO
        let lastWeek = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 7, day: 30, hour: 9))
        )
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 6, hour: 9))
        )
        let overdue = CheckInItem(
            title: "上周欠下的",
            category: .work,
            scheduledStart: lastWeek,
            plannedMinutes: 40
        )
        let snapshot = PlanSnapshot(checkInItems: [overdue])

        XCTAssertTrue(
            overdue.isVisibleInChecklist(
                on: today,
                carriesOverUnfinished: true,
                calendar: calendar
            )
        )
        XCTAssertFalse(
            overdue.isVisibleInChecklist(
                on: today,
                carriesOverUnfinished: false,
                calendar: calendar
            )
        )
        XCTAssertEqual(
            WidgetSelectionEngine.todayPlans(
                in: snapshot,
                now: today,
                carriesOverUnfinished: true,
                calendar: calendar
            ).map(\.title),
            ["上周欠下的"]
        )
        XCTAssertTrue(
            WidgetSelectionEngine.todayPlans(
                in: snapshot,
                now: today,
                carriesOverUnfinished: false,
                calendar: calendar
            ).isEmpty
        )
    }

    func testChecklistNeverShowsPlansScheduledForALaterDay() throws {
        let calendar = Calendar.mojiISO
        let today = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 6, hour: 9))
        )
        let later = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 9, hour: 9))
        )
        let future = CheckInItem(
            title: "周日再说",
            category: .work,
            scheduledStart: later,
            plannedMinutes: 30
        )

        XCTAssertFalse(
            future.isVisibleInChecklist(
                on: today,
                carriesOverUnfinished: true,
                calendar: calendar
            )
        )
        XCTAssertTrue(
            WidgetSelectionEngine.todayPlans(
                in: PlanSnapshot(checkInItems: [future]),
                now: today,
                carriesOverUnfinished: true,
                calendar: calendar
            ).isEmpty
        )
    }

    func testCarryOverSealCountsWholeDaysAndOnlyMarksLatePlans() throws {
        let calendar = Calendar.mojiISO
        let plannedDay = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 3, hour: 22))
        )
        let sameDay = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 3, hour: 1))
        )
        let nextMorning = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 4, hour: 7))
        )
        let threeDaysOn = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 8, day: 6, hour: 23))
        )

        // Whole days apart, not hours: a plan set for tonight is not "late"
        // this morning, and one from last night is one day late today.
        XCTAssertNil(
            CarryOverSeal.daysLate(
                scheduledStart: plannedDay,
                on: sameDay,
                calendar: calendar
            )
        )
        XCTAssertEqual(
            CarryOverSeal.daysLate(
                scheduledStart: plannedDay,
                on: nextMorning,
                calendar: calendar
            ),
            1
        )
        XCTAssertEqual(
            CarryOverSeal.daysLate(
                scheduledStart: plannedDay,
                on: threeDaysOn,
                calendar: calendar
            ),
            3
        )
    }

    func testCarryOverSealTextStaysShortEnoughToCarve() {
        XCTAssertEqual(CarryOverSeal.text(daysLate: 1), "1")
        XCTAssertEqual(CarryOverSeal.text(daysLate: 17), "17")
        XCTAssertEqual(CarryOverSeal.text(daysLate: 99), "99")
        XCTAssertEqual(CarryOverSeal.text(daysLate: 100), "99+")
        XCTAssertEqual(CarryOverSeal.text(daysLate: 0), "")
    }

    func testWidgetBoardRefreshesJustAfterMidnight() {
        let calendar = Calendar.current
        let now = Date()

        let refresh = WidgetSelectionEngine.refreshDate(after: now, calendar: calendar)

        let tomorrow = calendar.date(
            byAdding: .day,
            value: 1,
            to: calendar.startOfDay(for: now)
        )
        XCTAssertEqual(refresh, tomorrow?.addingTimeInterval(60))
        XCTAssertGreaterThan(refresh, now)
    }

    func testPlanEditorDeepLinkRoundTrips() {
        let id = UUID()
        let url = try! XCTUnwrap(MojiDeepLink.planEditor(id))

        XCTAssertEqual(url.host, "plan")
        XCTAssertEqual(MojiDeepLink.planID(from: url), id)
        XCTAssertNil(
            MojiDeepLink.planID(from: URL(string: "moji://countdowns")!)
        )
        XCTAssertNil(
            MojiDeepLink.planID(from: URL(string: "moji://plan?id=not-a-uuid")!)
        )
    }

    // MARK: - Inherited backup settings

    func testNewestBackupInFolderWinsOverOlderCopies() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: folder,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: folder) }

        let old = folder.appendingPathComponent("Moji-20250101.mojibackup")
        let newest = folder.appendingPathComponent(
            ExternalBackupService.backupFileName
        )
        let unrelated = folder.appendingPathComponent("笔记.txt")
        try Data("old".utf8).write(to: old)
        try Data("newest".utf8).write(to: newest)
        try Data("noise".utf8).write(to: unrelated)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1_600_000_000)],
            ofItemAtPath: old.path
        )
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1_700_000_000)],
            ofItemAtPath: newest.path
        )

        let picked = ExternalBackupService.newestBackupURL(in: folder)

        XCTAssertEqual(picked?.lastPathComponent, ExternalBackupService.backupFileName)
    }

    func testEmptyFolderHasNoBackupToRestore() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: folder,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: folder) }

        XCTAssertNil(ExternalBackupService.newestBackupURL(in: folder))
    }

    /// The whole point of the inherited backup: a restored install has to end
    /// up with the same retention and know which folder it came from.
    func testBackupCarriesRetentionAndFolderNameAcrossAnImport() throws {
        let defaults = SharedPersistence.sharedDefaults
        let originalBackup = try SharedPersistence.exportBackup()
        let originalRetention = defaults.object(forKey: "minuteplan.backup.retentionDays")
        let originalFolder = defaults.object(forKey: "minuteplan.backup.folderName")
        defer {
            _ = try? SharedPersistence.importBackup(originalBackup)
            defaults.removeObject(forKey: "minuteplan.backup.expectedFolderName")
            if let originalRetention {
                defaults.set(originalRetention, forKey: "minuteplan.backup.retentionDays")
            } else {
                defaults.removeObject(forKey: "minuteplan.backup.retentionDays")
            }
            if let originalFolder {
                defaults.set(originalFolder, forKey: "minuteplan.backup.folderName")
            } else {
                defaults.removeObject(forKey: "minuteplan.backup.folderName")
            }
        }

        defaults.set(BackupRetention.oneYear.rawValue, forKey: "minuteplan.backup.retentionDays")
        defaults.set("Moji 备份", forKey: "minuteplan.backup.folderName")
        let exported = try SharedPersistence.exportBackup()

        // Simulate the fresh install: no retention, no folder, no bookmark.
        defaults.removeObject(forKey: "minuteplan.backup.retentionDays")
        defaults.removeObject(forKey: "minuteplan.backup.folderName")
        defaults.removeObject(forKey: "minuteplan.backup.expectedFolderName")

        _ = try SharedPersistence.importBackup(exported)

        XCTAssertEqual(
            defaults.integer(forKey: "minuteplan.backup.retentionDays"),
            BackupRetention.oneYear.rawValue
        )
        XCTAssertEqual(
            defaults.string(forKey: "minuteplan.backup.expectedFolderName"),
            "Moji 备份"
        )
        // The folder itself is never claimed by an import: that bookmark only
        // belonged to the install that made it.
        XCTAssertNil(defaults.string(forKey: "minuteplan.backup.folderName"))
    }

}

/// A save posts UserDefaults.didChangeNotification while the persistence lock is
/// held, and SwiftUI may run pending view actions inside it that read the
/// snapshot again. Before 1.8.0 that read waited on the same thread's lock and
/// the app froze. The save runs on a worker thread here, so a regression fails
/// after the timeout instead of hanging the whole run.
final class MojiPersistenceReentryTests: XCTestCase {
    func testReadingDuringSaveNotificationDoesNotWaitOnItself() throws {
        let original = SharedPersistence.load()
        defer { SharedPersistence.replace(with: original) }

        final class Box: @unchecked Sendable {
            let lock = NSLock()
            var worker: Thread?
            var entered = false
            var nested: PlanSnapshot?
        }
        let box = Box()
        let observer = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: SharedPersistence.sharedDefaults,
            queue: nil
        ) { _ in
            box.lock.lock()
            let first = box.worker === Thread.current && !box.entered
            if first { box.entered = true }
            box.lock.unlock()
            guard first else { return }
            let snapshot = SharedPersistence.load()
            // A nested write must also go through, and must not be overwritten
            // by the outer save that is still on the stack.
            SharedPersistence.mutate { $0.memos.removeAll { $0.content == "re-entry probe" } }
            box.lock.lock()
            box.nested = snapshot
            box.lock.unlock()
        }
        defer { NotificationCenter.default.removeObserver(observer) }

        let memo = MemoItem(content: "re-entry probe")
        let finished = expectation(description: "save returns")
        Thread.detachNewThread {
            box.lock.lock()
            box.worker = Thread.current
            box.lock.unlock()
            SharedPersistence.mutate { $0.memos.append(memo) }
            finished.fulfill()
        }
        wait(for: [finished], timeout: 10)

        box.lock.lock()
        let nested = box.nested
        box.lock.unlock()
        let nestedSnapshot = try XCTUnwrap(nested)
        XCTAssertTrue(nestedSnapshot.memos.contains { $0.id == memo.id }, "the nested read sees the save in progress")
        XCTAssertFalse(SharedPersistence.load().memos.contains { $0.id == memo.id }, "the nested write is kept")
    }
}

final class MojiAppGroupTests: XCTestCase {
    private let declared = AppGroupResolver.declaredIdentifier

    // MARK: Resolving the group

    func testSignedCopyOfDeclaredGroupComesFirst() {
        let candidates = AppGroupResolver.candidates(
            declared: declared,
            infoPlistGroups: ["group.other.tool", "\(declared).ABCDE12345"],
            signedGroups: ["\(declared).ABCDE12345"]
        )
        XCTAssertEqual(candidates, ["\(declared).ABCDE12345", "group.other.tool", declared])
    }

    func testShortestDerivedGroupWins() {
        let candidates = AppGroupResolver.candidates(
            declared: declared,
            infoPlistGroups: ["\(declared).TEAM.extra", "\(declared).TEAM"],
            signedGroups: []
        )
        XCTAssertEqual(candidates.first, "\(declared).TEAM")
        XCTAssertFalse(AppGroupResolver.isDerived("group.com.raydon.minuteplanner", from: declared))
    }

    func testSignatureIsUsedWhenInfoPlistHasNoGroups() {
        // SideStore 0.7 no longer writes ALTAppGroups into Info.plist.
        let resolved = AppGroupResolver.resolve(
            declared: declared,
            infoPlistGroups: [],
            signedGroups: ["\(declared).Z6XP9AHRBT"],
            isAvailable: { $0 == "\(self.declared).Z6XP9AHRBT" }
        )
        XCTAssertEqual(resolved, "\(declared).Z6XP9AHRBT")
    }

    func testUnavailableGroupsFallBackToDeclaredIdentifier() {
        let resolved = AppGroupResolver.resolve(
            declared: declared,
            infoPlistGroups: ["\(declared).TEAM"],
            signedGroups: ["\(declared).TEAM"],
            isAvailable: { _ in false }
        )
        XCTAssertEqual(resolved, declared)
    }

    func testUnsignedTestHostKeepsDeclaredIdentifier() {
        XCTAssertEqual(SharedPersistence.appGroupIdentifier, declared)
    }

    // MARK: Reading entitlements from a code signature

    func testEntitlementsAreReadFromThinMachO() throws {
        let binary = try machO(entitlements: [
            CodeSignatureEntitlements.applicationGroupsKey: ["\(declared).TEAM"],
            "get-task-allow": true
        ])
        let entitlements = try XCTUnwrap(CodeSignatureEntitlements.entitlements(inMachO: binary))
        XCTAssertEqual(entitlements[CodeSignatureEntitlements.applicationGroupsKey] as? [String], ["\(declared).TEAM"])
        XCTAssertEqual(entitlements["get-task-allow"] as? Bool, true)
    }

    func testEntitlementsAreReadFromFatMachO() throws {
        let thin = try machO(entitlements: [CodeSignatureEntitlements.applicationGroupsKey: [declared]])
        var fat = Data()
        fat.appendBigEndian(0xCAFE_BABE)
        fat.appendBigEndian(1)
        fat.appendBigEndian(0x0100_000C)
        fat.appendBigEndian(0)
        fat.appendBigEndian(64)
        fat.appendBigEndian(UInt32(thin.count))
        fat.appendBigEndian(12)
        fat.append(Data(count: 64 - fat.count))
        fat.append(thin)
        let entitlements = try XCTUnwrap(CodeSignatureEntitlements.entitlements(inMachO: fat))
        XCTAssertEqual(entitlements[CodeSignatureEntitlements.applicationGroupsKey] as? [String], [declared])
    }

    func testExecutableFileOnDiskYieldsItsGroups() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("moji-entitlements-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: url) }
        try machO(entitlements: [CodeSignatureEntitlements.applicationGroupsKey: ["\(declared).TEAM"]]).write(to: url)
        XCTAssertEqual(CodeSignatureEntitlements.applicationGroups(ofExecutableAt: url), ["\(declared).TEAM"])
        XCTAssertEqual(
            CodeSignatureEntitlements.applicationGroups(ofExecutableAt: url.appendingPathExtension("missing")),
            []
        )
    }

    func testMalformedBinariesYieldNoEntitlements() throws {
        XCTAssertNil(CodeSignatureEntitlements.entitlements(inMachO: Data()))
        XCTAssertNil(CodeSignatureEntitlements.entitlements(inMachO: Data("not a binary".utf8)))
        XCTAssertNil(CodeSignatureEntitlements.entitlements(inMachO: try machO(entitlements: nil)))

        let valid = try machO(entitlements: [CodeSignatureEntitlements.applicationGroupsKey: [declared]])
        for length in 0..<valid.count {
            // Every truncation must fail cleanly instead of reading past the end.
            if let partial = CodeSignatureEntitlements.entitlements(inMachO: valid.prefix(length)) {
                XCTAssertEqual(partial[CodeSignatureEntitlements.applicationGroupsKey] as? [String], [declared])
            }
        }
        var wrongSuperBlobMagic = valid
        wrongSuperBlobMagic[wrongSuperBlobMagic.startIndex + 72] = 0
        XCTAssertNil(CodeSignatureEntitlements.entitlements(inMachO: wrongSuperBlobMagic))
    }

    // MARK: Moving data into the re-signed group

    func testDataLeftInSandboxIsCopiedIntoUnusedGroup() throws {
        let fixture = try MigrationFixture()
        defer { fixture.tearDown() }
        let onlyKey = "minuteplan.tests.legacyOnly.\(UUID().uuidString)"
        let newerPrefixKey = "moji.tests.legacyOnly.\(UUID().uuidString)"
        let foreignKey = "com.example.tests.notMoji.\(UUID().uuidString)"
        fixture.legacy.set(42, forKey: onlyKey)
        fixture.legacy.set(true, forKey: newerPrefixKey)
        fixture.legacy.set("ignore", forKey: foreignKey)
        fixture.legacy.set(try encodedSnapshot(title: "旧计划"), forKey: SharedPersistence.snapshotKey)
        try fixture.writeLegacyFile("snapshot.json", try encodedSnapshot(title: "旧计划"))
        try fixture.writeLegacyFile("before-restore.mojibackup", Data("recovery".utf8))

        XCTAssertTrue(fixture.migrate())

        XCTAssertEqual(fixture.target.integer(forKey: onlyKey), 42)
        XCTAssertEqual(fixture.target.object(forKey: newerPrefixKey) as? Bool, true)
        XCTAssertNil(fixture.target.object(forKey: foreignKey))
        XCTAssertEqual(
            fixture.target.data(forKey: SharedPersistence.snapshotKey),
            fixture.legacy.data(forKey: SharedPersistence.snapshotKey)
        )
        XCTAssertEqual(try fixture.targetFile("snapshot.json"), try fixture.legacyFile("snapshot.json"))
        XCTAssertEqual(try fixture.targetFile("before-restore.mojibackup"), Data("recovery".utf8))
        XCTAssertEqual(fixture.target.string(forKey: SharedPersistence.appGroupMigrationKey), "copied")
        // The old copy stays where it was.
        XCTAssertEqual(fixture.legacy.integer(forKey: onlyKey), 42)
        XCTAssertNotNil(try fixture.legacyFile("snapshot.json"))
    }

    func testGroupThatAlreadyHoldsDataKeepsIt() throws {
        let fixture = try MigrationFixture()
        defer { fixture.tearDown() }
        let sharedKey = "minuteplan.tests.both.\(UUID().uuidString)"
        let onlyKey = "minuteplan.tests.legacyOnly.\(UUID().uuidString)"
        let groupSnapshot = try encodedSnapshot(title: "新分组里的计划")
        fixture.target.set(1, forKey: sharedKey)
        fixture.target.set(groupSnapshot, forKey: SharedPersistence.snapshotKey)
        try fixture.writeTargetFile("snapshot.json", groupSnapshot)
        fixture.legacy.set(2, forKey: sharedKey)
        fixture.legacy.set(3, forKey: onlyKey)
        fixture.legacy.set(try encodedSnapshot(title: "旧计划"), forKey: SharedPersistence.snapshotKey)
        try fixture.writeLegacyFile("snapshot.json", try encodedSnapshot(title: "旧计划"))

        XCTAssertTrue(fixture.migrate())

        XCTAssertEqual(fixture.target.integer(forKey: sharedKey), 1)
        XCTAssertEqual(fixture.target.integer(forKey: onlyKey), 3)
        XCTAssertEqual(fixture.target.data(forKey: SharedPersistence.snapshotKey), groupSnapshot)
        XCTAssertEqual(try fixture.targetFile("snapshot.json"), groupSnapshot)
    }

    func testMigrationRunsOnlyOnce() throws {
        let fixture = try MigrationFixture()
        defer { fixture.tearDown() }
        let key = "minuteplan.tests.once.\(UUID().uuidString)"
        fixture.legacy.set("old", forKey: key)
        XCTAssertTrue(fixture.migrate())
        fixture.target.set("changed in the group", forKey: key)
        fixture.legacy.set("old again", forKey: key)

        XCTAssertFalse(fixture.migrate())
        XCTAssertEqual(fixture.target.string(forKey: key), "changed in the group")
    }

    func testFreshInstallHasNothingToCopy() throws {
        let fixture = try MigrationFixture()
        defer { fixture.tearDown() }
        let removed = fixture.legacy.dictionaryRepresentation().keys.filter {
            $0.hasPrefix("minuteplan.") || $0.hasPrefix("moji.")
        }
        try XCTSkipIf(!removed.isEmpty, "The test host's own defaults already carry Moji keys.")

        XCTAssertFalse(fixture.migrate())
        XCTAssertEqual(fixture.target.string(forKey: SharedPersistence.appGroupMigrationKey), "nothing-to-copy")
    }

    // MARK: Helpers

    private func encodedSnapshot(title: String) throws -> Data {
        let plan = CheckInItem(
            title: title,
            category: .study,
            scheduledStart: Date(timeIntervalSince1970: 1_790_000_000),
            plannedMinutes: 30,
            note: "",
            plannedDurationEnabled: true
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(PlanSnapshot(checkInItems: [plan]))
    }

    /// A 64-bit Mach-O with one unrelated load command and, unless
    /// `entitlements` is nil, a code signature holding a placeholder code
    /// directory and an entitlements blob.
    private func machO(entitlements: [String: Any]?) throws -> Data {
        var binary = Data()
        let commandsSize: UInt32 = entitlements == nil ? 24 : 40
        for value: UInt32 in [0xFEED_FACF, 0x0100_000C, 0, 2, entitlements == nil ? 1 : 2, commandsSize, 0, 0] {
            binary.appendLittleEndian(value)
        }
        for value: UInt32 in [0x2, 24, 0, 0, 0, 0] {
            binary.appendLittleEndian(value)
        }
        guard let entitlements else { return binary }

        let plist = try PropertyListSerialization.data(fromPropertyList: entitlements, format: .xml, options: 0)
        var codeDirectory = Data()
        codeDirectory.appendBigEndian(0xFADE_0C02)
        codeDirectory.appendBigEndian(8)
        var entitlementsBlob = Data()
        entitlementsBlob.appendBigEndian(0xFADE_7171)
        entitlementsBlob.appendBigEndian(UInt32(8 + plist.count))
        entitlementsBlob.append(plist)
        let indexEnd: UInt32 = 12 + 2 * 8
        var superBlob = Data()
        superBlob.appendBigEndian(0xFADE_0CC0)
        superBlob.appendBigEndian(indexEnd + UInt32(codeDirectory.count + entitlementsBlob.count))
        superBlob.appendBigEndian(2)
        superBlob.appendBigEndian(0)
        superBlob.appendBigEndian(indexEnd)
        superBlob.appendBigEndian(5)
        superBlob.appendBigEndian(indexEnd + UInt32(codeDirectory.count))
        superBlob.append(codeDirectory)
        superBlob.append(entitlementsBlob)

        let signatureOffset: UInt32 = 32 + 40
        for value: UInt32 in [0x1D, 16, signatureOffset, UInt32(superBlob.count)] {
            binary.appendLittleEndian(value)
        }
        XCTAssertEqual(binary.count, Int(signatureOffset))
        binary.append(superBlob)
        return binary
    }
}

private struct MigrationFixture {
    let legacySuite = "com.raydon.moji.legacy-group-tests.\(UUID().uuidString)"
    let targetSuite = "com.raydon.moji.target-group-tests.\(UUID().uuidString)"
    let legacy: UserDefaults
    let target: UserDefaults
    let root = FileManager.default.temporaryDirectory
        .appendingPathComponent("moji-group-migration-\(UUID().uuidString)", isDirectory: true)
    var legacyDirectory: URL { root.appendingPathComponent("legacy/MinutePlan", isDirectory: true) }
    var targetDirectory: URL { root.appendingPathComponent("group/MinutePlan", isDirectory: true) }

    init() throws {
        legacy = try XCTUnwrap(UserDefaults(suiteName: legacySuite))
        target = try XCTUnwrap(UserDefaults(suiteName: targetSuite))
        try FileManager.default.createDirectory(at: legacyDirectory, withIntermediateDirectories: true)
    }

    func migrate() -> Bool {
        SharedPersistence.migrateStorage(
            fromDefaults: legacy,
            directory: legacyDirectory,
            toDefaults: target,
            directory: targetDirectory
        )
    }

    func writeLegacyFile(_ name: String, _ data: Data) throws {
        try data.write(to: legacyDirectory.appendingPathComponent(name))
    }

    func writeTargetFile(_ name: String, _ data: Data) throws {
        try FileManager.default.createDirectory(at: targetDirectory, withIntermediateDirectories: true)
        try data.write(to: targetDirectory.appendingPathComponent(name))
    }

    func legacyFile(_ name: String) throws -> Data {
        try Data(contentsOf: legacyDirectory.appendingPathComponent(name))
    }

    func targetFile(_ name: String) throws -> Data {
        try Data(contentsOf: targetDirectory.appendingPathComponent(name))
    }

    func tearDown() {
        legacy.removePersistentDomain(forName: legacySuite)
        target.removePersistentDomain(forName: targetSuite)
        try? FileManager.default.removeItem(at: root)
    }
}

private extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.bigEndian) { append(contentsOf: $0) }
    }

    mutating func appendLittleEndian(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}

/// What the model answers is read here, never trusted: dates are worked out
/// locally, unknown values are dropped, and nothing changes until accepted.
final class MojiAIDraftingTests: XCTestCase {
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Asia/Tokyo")!; return c }
    private func date(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }
    /// Thursday 2026-11-19, noon in Tokyo.
    private var now: Date { date("2026-11-19T03:00:00Z") }
    private let categories: [RecordCategory] = [.study, .work]

    func testDayIsComputedLocallyFromTheThreeForms() {
        func day(_ object: [String: Any]) -> Date? { AIDrafting.day(in: object, now: now, calendar: calendar) }
        XCTAssertEqual(day(["date": "2026-12-05"]), date("2026-12-04T15:00:00Z"))
        XCTAssertEqual(day(["offset_days": 1]), date("2026-11-19T15:00:00Z"))
        XCTAssertEqual(day(["weekday": 6, "week_offset": 0]), date("2026-11-20T15:00:00Z"))
        // “周三” said on a Thursday is the coming Wednesday, not yesterday.
        XCTAssertEqual(day(["weekday": 3, "week_offset": 0]), date("2026-11-24T15:00:00Z"))
        XCTAssertEqual(day(["weekday": 1, "week_offset": 1]), date("2026-11-22T15:00:00Z"))
        XCTAssertNil(day(["date": "2026-02-30"]))
        XCTAssertNil(day(["date": NSNull(), "offset_days": NSNull(), "weekday": 9]))
        XCTAssertNil(day(["offset_days": -3]))
        // 0 and 1 parsed from JSON are numbers, true and false are not.
        XCTAssertEqual(try AIDrafting.object(in: #"{"a":0,"b":1,"c":1.0,"d":" 2 ","e":true,"f":false,"g":null,"h":1e9}"#)
            .mapValues { AIDrafting.integer($0) }, ["a": 0, "b": 1, "c": 1, "d": 2, "e": nil, "f": nil, "g": nil, "h": nil])
        XCTAssertEqual(try day(AIDrafting.object(in: #"{"offset_days":1}"#)), date("2026-11-19T15:00:00Z"))
        XCTAssertNil(try day(AIDrafting.object(in: #"{"offset_days":true}"#)))
    }

    func testEntrySuggestionReadsAFencedAnswerAndDropsWhatIsNotOffered() throws {
        let answer = "好的：\n```json\n{\"title\":\"交保险计划书\",\"offset_days\":1,\"part\":\"exact\",\"time\":\"15:00\",\"minutes\":45,\"category\":\"学习\",\"repeat\":\"weekly\",\"items\":[]}\n```"
        let suggestion = try AIDrafting.entrySuggestion(from: answer, original: "明天下午三点交保险计划书 45分钟", now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(suggestion.title, "交保险计划书")
        XCTAssertEqual(suggestion.day, date("2026-11-19T15:00:00Z"))
        XCTAssertEqual(suggestion.scheduleKind, .exactTime)
        XCTAssertEqual(suggestion.hour, 15); XCTAssertEqual(suggestion.minute, 0)
        XCTAssertEqual(suggestion.minutes, 45)
        XCTAssertEqual(suggestion.category, .study)
        XCTAssertEqual(suggestion.repeatRule, .weekly)
        XCTAssertTrue(suggestion.parts.isEmpty)
        XCTAssertEqual(suggestion.chips(now: now, calendar: calendar), ["明天", "15:00", DurationText.full(minutes: 45), "学习", PlanRepeatRule.weekly.displayName])

        let loose = try AIDrafting.entrySuggestion(
            from: #"{"title":"读书","offset_days":0,"part":"exact","time":"25:00","minutes":99999,"category":"钓鱼","repeat":"yearly","items":["读书"]}"#,
            original: "读书", now: now, categories: categories, calendar: calendar)
        XCTAssertTrue(loose.isEmpty, "today, an impossible time, an unknown type and a single item leave nothing to suggest")
        XCTAssertNil(loose.title)
        XCTAssertThrowsError(try AIDrafting.entrySuggestion(from: "没有 JSON", original: "读书", now: now, categories: categories))
    }

    func testEntrySuggestionSplitsSeveralThings() throws {
        let suggestion = try AIDrafting.entrySuggestion(
            from: #"{"title":null,"items":["买菜","取快递","买菜","给妈妈打电话"]}"#,
            original: "买菜、取快递、给妈妈打电话", now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(suggestion.parts, ["买菜", "取快递", "给妈妈打电话"])
        XCTAssertFalse(suggestion.hasDetails); XCTAssertFalse(suggestion.isEmpty)
    }

    func testAppliedSuggestionChangesOnlyRecognisedFields() throws {
        var original = CheckInItem(title: "明天下午三点交保险计划书 45分钟", category: .work, scheduledStart: date("2026-11-18T15:00:00Z"),
                                   plannedMinutes: 25, scheduleKind: .allDay, detailsConfigured: false, plannedDurationEnabled: false)
        original.reminderMinutesBefore = 10
        var suggestion = EntrySuggestion(title: "交保险计划书", day: date("2026-11-19T15:00:00Z"), scheduleKind: .exactTime, hour: 15, minute: 0, minutes: 45)
        let updated = try XCTUnwrap(suggestion.applied(to: original, calendar: calendar))
        XCTAssertEqual(updated.id, original.id)
        XCTAssertEqual(updated.title, "交保险计划书")
        XCTAssertEqual(updated.scheduledStart, date("2026-11-20T06:00:00Z"))
        XCTAssertEqual(updated.effectiveScheduleKind, .exactTime)
        XCTAssertEqual(updated.plannedDurationMinutes, 45)
        XCTAssertEqual(updated.category, .work, "a type the sentence did not name stays")
        XCTAssertEqual(updated.reminderMinutesBefore, 10)
        XCTAssertEqual(updated.status, .planned)

        suggestion = EntrySuggestion(category: .study)
        let typed = try XCTUnwrap(suggestion.applied(to: original, calendar: calendar))
        XCTAssertEqual(typed.category, .study)
        XCTAssertEqual(typed.scheduledStart, original.scheduledStart)
        XCTAssertFalse(typed.hasPlannedDuration)
        XCTAssertNil(EntrySuggestion().applied(to: original, calendar: calendar))

        // A length on its own must not move a plan that already has a time.
        let timed = CheckInItem(title: "开会", category: .work, scheduledStart: date("2026-11-19T00:30:00Z"), plannedMinutes: 25, plannedDurationEnabled: false)
        let longer = try XCTUnwrap(EntrySuggestion(minutes: 60).applied(to: timed, calendar: calendar))
        XCTAssertEqual(longer.scheduledStart, timed.scheduledStart)
        XCTAssertEqual(longer.effectiveScheduleKind, .exactTime)
        XCTAssertEqual(longer.plannedDurationMinutes, 60)
    }

    func testStepsOfACarriedOverPlanStartToday() throws {
        let plan = CheckInItem(title: "写完研究计划书第三节", category: .study, scheduledStart: date("2026-11-16T01:00:00Z"), plannedMinutes: 90)
        let drafts = try AIDrafting.steps(from: #"{"steps":[{"title":"列提纲","minutes":10},"查两篇文献",{"title":"  "},{"minutes":5}]}"#,
                                          plan: plan, now: now, calendar: calendar)
        XCTAssertEqual(drafts.map(\.title), ["列提纲", "查两篇文献"])
        XCTAssertEqual(drafts.map(\.minutes), [10, nil])
        XCTAssertTrue(drafts.allSatisfy { $0.day == date("2026-11-18T15:00:00Z") && $0.category == .study && $0.scheduleKind == .allDay })
        let saved = drafts[0].plan(note: "拆自：写完研究计划书第三节", calendar: calendar)
        XCTAssertEqual(saved.note, "拆自：写完研究计划书第三节")
        XCTAssertEqual(saved.effectiveScheduleKind, .allDay)
        XCTAssertEqual(saved.plannedDurationMinutes, 10)
        XCTAssertNil(drafts[1].plan(calendar: calendar).plannedDurationMinutes)
        XCTAssertThrowsError(try AIDrafting.steps(from: #"{"steps":[]}"#, plan: plan, now: now, calendar: calendar))

        let future = CheckInItem(title: "订机票", category: .work, scheduledStart: date("2026-11-25T01:00:00Z"), plannedMinutes: 25)
        XCTAssertEqual(try AIDrafting.steps(from: #"{"steps":["比价"]}"#, plan: future, now: now, calendar: calendar).first?.day,
                       date("2026-11-24T15:00:00Z"))
    }

    func testReflectionShowsOnlyEvidenceFoundInTheRecord() throws {
        let plans = [CheckInItem(title: "背单词", category: .study, scheduledStart: now, plannedMinutes: 25),
                     CheckInItem(title: "写周报", category: .work, scheduledStart: now, plannedMinutes: 25)]
        let draft = try AIDrafting.reflection(
            from: #"{"note":"前半周很稳。","next_focus":"先拆第一步","evidence":[{"point":"按时完成","plans":[1,5]},{"point":"凭空的结论","plans":[9]},{"point":"没有依据"}]}"#,
            plans: plans)
        XCTAssertEqual(draft.note, "前半周很稳。"); XCTAssertEqual(draft.nextFocus, "先拆第一步")
        XCTAssertEqual(draft.evidence, [ReflectionDraft.Evidence(point: "按时完成", planTitles: ["背单词"])])
        XCTAssertThrowsError(try AIDrafting.reflection(from: #"{"note":"","evidence":[]}"#, plans: plans))
        XCTAssertTrue(try AIDrafting.reflection(from: #"{"note":"空的一周","evidence":[{"point":"x","plans":[1]}]}"#, plans: []).evidence.isEmpty)
    }

    func testWeekRecordNumbersPlansAndSendsNoNotes() {
        var done = CheckInItem(title: "背单词", category: .study, scheduledStart: date("2026-11-17T01:00:00Z"), plannedMinutes: 25, note: "私人备注")
        done.status = .completed; done.completedAt = date("2026-11-17T02:00:00Z")
        let open = CheckInItem(title: "写周报", category: .work, scheduledStart: date("2026-11-18T01:00:00Z"), plannedMinutes: 30)
        let other = CheckInItem(title: "上上周的事", category: .work, scheduledStart: date("2026-11-03T01:00:00Z"), plannedMinutes: 30)
        var snapshot = PlanSnapshot()
        snapshot.checkInItems = [done, open, other]
        let record = AIDrafting.weekRecord(snapshot: snapshot, week: now, now: now, calendar: calendar)
        // Done first, then what is still open, oldest first; the numbers are what a point may cite.
        XCTAssertEqual(record.plans.map(\.title), ["背单词", "上上周的事", "写周报"])
        XCTAssertTrue(record.text.contains("[1] 背单词")); XCTAssertTrue(record.text.contains("[2] 上上周的事")); XCTAssertTrue(record.text.contains("[3] 写周报"))
        XCTAssertTrue(record.text.contains("完成 1 项，没做完 2 项，跳过 0 项"))
        XCTAssertTrue(record.text.contains("11月16日–11月22日"))
        XCTAssertFalse(record.text.contains("私人备注"))
    }

    func testNextWeekAndPreparationDraftsStayInsideTheirDays() throws {
        let next = try AIDrafting.nextWeekDrafts(
            from: #"{"plans":[{"title":"补第三节","weekday":2,"part":"afternoon","minutes":60,"category":"学习","reason":"本周没做完"},{"title":"没有星期","weekday":null},{"title":"复盘","weekday":7,"category":"钓鱼"}]}"#,
            week: now, fallbackCategory: .work, categories: categories, calendar: calendar)
        XCTAssertEqual(next.map(\.title), ["补第三节", "复盘"])
        XCTAssertEqual(next.map(\.day), [date("2026-11-23T15:00:00Z"), date("2026-11-28T15:00:00Z")])
        XCTAssertEqual(next[0].scheduleKind, .afternoon); XCTAssertEqual(next[0].reason, "本周没做完")
        XCTAssertEqual(next[1].category, .work, "an unknown type falls back, it is never invented")

        let target = date("2026-11-28T15:00:00Z")
        let prep = try AIDrafting.preparationDrafts(
            from: #"{"plans":[{"title":"当天核对","days_before":0},{"title":"太早","days_before":30},{"title":"前三天","days_before":3},{"title":"负数","days_before":-2}]}"#,
            target: target, now: now, fallbackCategory: .study, categories: categories, calendar: calendar)
        XCTAssertEqual(prep.map(\.title), ["太早", "前三天", "当天核对"])
        XCTAssertEqual(prep.map(\.day), [date("2026-11-18T15:00:00Z"), date("2026-11-25T15:00:00Z"), target])
    }

    func testImportedPlansNeverLandInThePast() throws {
        let drafts = try AIDrafting.importDrafts(
            from: #"{"plans":[{"title":"交终稿","date":"2026-11-01","part":"exact","time":"17:00"},{"title":"预约体检","weekday":5,"week_offset":0,"part":"morning","minutes":20},{"title":"回邮件"}]}"#,
            now: now, fallbackCategory: .study, categories: categories, calendar: calendar)
        XCTAssertEqual(drafts.map(\.day), [date("2026-11-18T15:00:00Z"), date("2026-11-19T15:00:00Z"), date("2026-11-18T15:00:00Z")])
        XCTAssertEqual(drafts[0].scheduleKind, .exactTime); XCTAssertEqual(drafts[0].hour, 17)
        XCTAssertEqual(drafts[0].plan(calendar: calendar).scheduledStart, date("2026-11-19T08:00:00Z"))
        XCTAssertEqual(drafts[1].scheduleKind, .morning); XCTAssertEqual(drafts[1].minutes, 20)
        XCTAssertEqual(drafts[2].scheduleKind, .allDay)
    }

    func testChecklistAndConversionHints() throws {
        XCTAssertEqual(try AIDrafting.checklist(from: #"{"items":["打印准考证"," 打印准考证 ","",null,"带两支笔"]}"#), ["打印准考证", "带两支笔"])
        XCTAssertThrowsError(try AIDrafting.checklist(from: #"{"items":[]}"#))
        let hints = try AIDrafting.conversionHints(
            from: #"{"rows":[{"index":0,"offset_days":1,"category":"学习"},{"index":1,"category":null},{"index":7,"offset_days":2},{"index":2,"weekday":5,"week_offset":0}]}"#,
            rowCount: 3, now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(hints, [0: ConversionHint(day: date("2026-11-19T15:00:00Z"), category: .study),
                               2: ConversionHint(day: date("2026-11-19T15:00:00Z"), category: nil)])
        XCTAssertEqual(hints[0]?.text(now: now, calendar: calendar), "明天 · 学习")
    }

    func testLibraryFilterTurnsNamedPeriodsIntoRanges() throws {
        let filter = try AIDrafting.libraryFilter(from: #"{"keyword":null,"category":"学习","status":"planned","period":"last_week"}"#,
                                                  now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(filter, LibraryFilter(keyword: "", category: "study", status: "planned", period: "range",
                                             from: date("2026-11-08T15:00:00Z"), to: date("2026-11-14T15:00:00Z")))
        let month = try AIDrafting.libraryFilter(from: #"{"keyword":"保险","period":"last_month","status":"weird"}"#,
                                                 now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(month.keyword, "保险"); XCTAssertEqual(month.status, "all")
        XCTAssertEqual(month.from, date("2026-09-30T15:00:00Z")); XCTAssertEqual(month.to, date("2026-10-30T15:00:00Z"))
        let range = try AIDrafting.libraryFilter(from: #"{"period":"range","from":"2026-11-01","to":"2026-11-10"}"#,
                                                 now: now, categories: categories, calendar: calendar)
        XCTAssertEqual(range.from, date("2026-10-31T15:00:00Z")); XCTAssertEqual(range.to, date("2026-11-09T15:00:00Z"))
        XCTAssertThrowsError(try AIDrafting.libraryFilter(from: #"{"status":"all","period":"all"}"#, now: now, categories: categories, calendar: calendar))
        XCTAssertThrowsError(try AIDrafting.libraryFilter(from: #"{"period":"range","from":"2026-11-10","to":"2026-11-01"}"#,
                                                          now: now, categories: categories, calendar: calendar))
    }

    func testOnlyTextWorthReadingIsSent() {
        XCTAssertFalse(AIDrafting.isWorthReading("买菜"))
        XCTAssertFalse(AIDrafting.isWorthReading("跑步"))
        XCTAssertTrue(AIDrafting.isWorthReading("明天下午三点交保险计划书"))
        XCTAssertTrue(AIDrafting.isWorthReading("买菜、取快递"))
        XCTAssertFalse(AIDrafting.isWorthReading(String(repeating: "明天", count: 150)))
        XCTAssertFalse(AIDrafting.looksLikeFilterSentence("保险"))
        XCTAssertTrue(AIDrafting.looksLikeFilterSentence("上周没做完的学习计划"))
        let plan = CheckInItem(title: "写第三节", category: .study, scheduledStart: now, plannedMinutes: 90, note: "参考中出 2019", plannedDurationEnabled: true)
        XCTAssertEqual(AIDrafting.breakdownInput(plan), "计划：写第三节\n备注：参考中出 2019\n预计投入：90 分钟")
    }

    func testDraftUndoTakesBackOnlyWhatWasNotTouchedSince() {
        let original = CheckInItem(title: "大计划", category: .study, scheduledStart: now, plannedMinutes: 25)
        var archived = original; archived.isArchived = true
        let first = CheckInItem(title: "第一步", category: .study, scheduledStart: now, plannedMinutes: 25)
        let second = CheckInItem(title: "第二步", category: .study, scheduledStart: now, plannedMinutes: 25)
        let undo = PlanDraftUndo(added: [first, second], changed: PlanWorkflowUndo(before: [original], after: [archived]))

        var state = PlanSnapshot()
        state.checkInItems = [archived, first, second]
        var untouched = state
        XCTAssertEqual(undo.apply(to: &untouched), 3)
        XCTAssertEqual(untouched.checkInItems, [original])

        var edited = second; edited.title = "第二步（改过）"
        state.checkInItems = [archived, first, edited]
        XCTAssertEqual(undo.apply(to: &state), 2)
        XCTAssertEqual(state.checkInItems, [original, edited], "a step edited after it was accepted stays")

        state.checkInItems = [archived, first, second]
        XCTAssertEqual(undo.apply(to: &state, blockedID: first.id), 2)
        XCTAssertEqual(state.checkInItems.map(\.id), [original.id, first.id])
    }

    func testFixtureAnswersUsedForScreenshotsAreReadable() throws {
        let today = Date()
        let entry = AIQAFixture.answer(instructions: AIDrafting.entryInstructions(now: today, categories: categories), input: "明天下午三点交保险计划书 45分钟")
        XCTAssertTrue(try AIDrafting.entrySuggestion(from: entry, original: "明天下午三点交保险计划书 45分钟", now: today, categories: categories).hasDetails)
        let split = AIQAFixture.answer(instructions: AIDrafting.entryInstructions(now: today, categories: categories), input: "买菜、取快递、给妈妈打电话")
        XCTAssertEqual(try AIDrafting.entrySuggestion(from: split, original: "买菜、取快递、给妈妈打电话", now: today, categories: categories).parts.count, 3)
        let plan = CheckInItem(title: "写完研究计划书第三节", category: .study, scheduledStart: today, plannedMinutes: 90)
        XCTAssertEqual(try AIDrafting.steps(from: AIQAFixture.answer(instructions: AIDrafting.breakdownInstructions, input: ""), plan: plan).count, 4)
        XCTAssertEqual(try AIDrafting.checklist(from: AIQAFixture.answer(instructions: AIDrafting.checklistInstructions, input: "")).count, 4)
        XCTAssertEqual(try AIDrafting.conversionHints(from: AIQAFixture.answer(instructions: AIDrafting.conversionInstructions(now: today, categories: categories), input: ""),
                                                      rowCount: 4, now: today, categories: categories).count, 2)
        XCTAssertEqual(try AIDrafting.reflection(from: AIQAFixture.answer(instructions: AIDrafting.reflectionInstructions, input: ""),
                                                 plans: [plan, plan, plan]).evidence.count, 2)
        XCTAssertEqual(try AIDrafting.nextWeekDrafts(from: AIQAFixture.answer(instructions: AIDrafting.nextWeekInstructions(categories: categories), input: ""),
                                                     week: today, fallbackCategory: .study, categories: categories).count, 4)
        XCTAssertEqual(try AIDrafting.preparationDrafts(from: AIQAFixture.answer(instructions: AIDrafting.preparationInstructions(daysLeft: 20, categories: categories), input: ""),
                                                        target: today.addingTimeInterval(20 * 86_400), now: today, fallbackCategory: .study, categories: categories).count, 4)
        XCTAssertEqual(try AIDrafting.libraryFilter(from: AIQAFixture.answer(instructions: AIDrafting.filterInstructions(now: today, categories: categories), input: ""),
                                                    now: today, categories: categories).status, "planned")
        XCTAssertEqual(try AIDrafting.importDrafts(from: AIQAFixture.answer(instructions: AIDrafting.importInstructions(now: today, categories: categories), input: ""),
                                                   now: today, fallbackCategory: .study, categories: categories).count, 3)
    }
}

/// The settings that decide whether anything is sent, and the request itself.
final class MojiAISettingsTests: XCTestCase {
    private var suite = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suite = "moji.tests.ai." + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    @MainActor
    func testNothingIsReadyUntilEnabledWithAKey() throws {
        let keys = AIMemoryKeyStore()
        let store = AISettingsStore(defaults: defaults, keys: keys)
        XCTAssertFalse(store.isReady)
        XCTAssertThrowsError(try store.credentials()) { XCTAssertEqual($0 as? AIRequestError, .disabled) }

        try store.change { $0.isEnabled = true }
        XCTAssertFalse(store.isReady, "on, but no key yet")
        XCTAssertThrowsError(try store.credentials()) { XCTAssertEqual($0 as? AIRequestError, .missingKey) }

        let added = try store.addProvider(.deepSeek, configuration: ProviderConfiguration(provider: .deepSeek), key: "  sk-test-123  ")
        XCTAssertEqual(added.id, "deepSeek"); XCTAssertTrue(added.activated)
        XCTAssertTrue(store.isReady)
        let credentials = try store.credentials()
        XCTAssertEqual(credentials.apiKey, "sk-test-123")
        XCTAssertEqual(credentials.configuration.model, AIProvider.deepSeek.models.first?.id)

        try store.change { $0.isEnabled = false }
        XCTAssertFalse(store.isReady)
    }

    @MainActor
    func testKeysAreNotWrittenIntoSettingsAndSurviveAReload() throws {
        let keys = AIMemoryKeyStore()
        let store = AISettingsStore(defaults: defaults, keys: keys)
        try store.addProvider(.deepSeek, configuration: ProviderConfiguration(provider: .deepSeek), key: "sk-secret-value")
        try store.change { $0.isEnabled = true }
        let saved = try XCTUnwrap(defaults.data(forKey: AISettingsStore.defaultsKey))
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("sk-secret-value"))

        let reloaded = AISettingsStore(defaults: defaults, keys: keys)
        XCTAssertTrue(reloaded.isReady)
        XCTAssertEqual(reloaded.settings, store.settings)
        XCTAssertFalse(AISettingsStore(defaults: defaults, keys: AIMemoryKeyStore()).isReady, "the same settings on a device without the key")
    }

    @MainActor
    func testAddingASecondProviderDoesNotSwitchAndTheOneInUseCannotBeRemoved() throws {
        let store = AISettingsStore(defaults: defaults, keys: AIMemoryKeyStore())
        try store.addProvider(.deepSeek, configuration: ProviderConfiguration(provider: .deepSeek), key: "k1")
        let second = try store.addProvider(.claude, configuration: ProviderConfiguration(provider: .claude), key: "k2")
        XCTAssertFalse(second.activated)
        XCTAssertEqual(store.settings.activeID, "deepSeek")
        XCTAssertEqual(store.listedProviderIDs, ["deepSeek", "claude"])

        try store.removeProvider("deepSeek")
        XCTAssertEqual(store.listedProviderIDs, ["deepSeek", "claude"])
        try store.removeProvider("claude")
        XCTAssertEqual(store.listedProviderIDs, ["deepSeek"])
        XCTAssertEqual(store.apiKey(for: "claude"), "")
    }

    @MainActor
    func testAnAddressThatIsNotHTTPSIsRefusedAndNothingIsSaved() {
        let keys = AIMemoryKeyStore()
        let store = AISettingsStore(defaults: defaults, keys: keys)
        var configuration = ProviderConfiguration(provider: .custom)
        configuration.baseURL = "http://example.com/v1"
        configuration.model = "some-model"
        XCTAssertThrowsError(try store.addProvider(.custom, configuration: configuration, key: "k")) {
            XCTAssertEqual($0 as? AIRequestError, .invalidURL)
        }
        XCTAssertEqual(store.apiKey(for: "custom"), "")
        XCTAssertNil(defaults.data(forKey: AISettingsStore.defaultsKey))
    }

    func testEndpointAcceptsABaseAddressOrAFullOne() throws {
        var configuration = ProviderConfiguration(provider: .custom)
        configuration.style = .chatCompletions
        configuration.baseURL = "https://api.example.com/v1/"
        XCTAssertEqual(try AIClient.endpoint(configuration: configuration).absoluteString, "https://api.example.com/v1/chat/completions")
        configuration.baseURL = "https://api.example.com/v1/chat/completions"
        XCTAssertEqual(try AIClient.endpoint(configuration: configuration).absoluteString, "https://api.example.com/v1/chat/completions")
        XCTAssertEqual(try AIClient.endpoint(configuration: configuration, models: true).absoluteString, "https://api.example.com/v1/models")
        configuration.style = .anthropicMessages
        XCTAssertEqual(try AIClient.endpoint(configuration: configuration).absoluteString, "https://api.example.com/v1/messages")
        for bad in ["http://api.example.com/v1", "https://user:pass@api.example.com/v1", "https://api.example.com/v1?key=1", "api.example.com", ""] {
            configuration.baseURL = bad
            XCTAssertThrowsError(try AIClient.endpoint(configuration: configuration), bad)
        }
    }

    func testRequestBodyCarriesOnlyTheInstructionsAndTheText() throws {
        var configuration = ProviderConfiguration(provider: .custom)
        configuration.model = "m"
        configuration.style = .chatCompletions
        let chat = AIClient.body(instructions: "指令", input: "原文", provider: .custom, configuration: configuration, model: "m")
        XCTAssertEqual(Set(chat.keys), ["model", "messages"])
        XCTAssertEqual(chat["messages"] as? [[String: String]], [["role": "system", "content": "指令"], ["role": "user", "content": "原文"]])

        configuration.style = .anthropicMessages
        let messages = AIClient.body(instructions: "指令", input: "原文", provider: .custom, configuration: configuration, model: "m")
        XCTAssertEqual(messages["system"] as? String, "指令")
        XCTAssertEqual(messages["messages"] as? [[String: String]], [["role": "user", "content": "原文"]])
        XCTAssertNotNil(messages["max_tokens"])

        configuration.style = .responses
        let responses = AIClient.body(instructions: "指令", input: "原文", provider: .custom, configuration: configuration, model: "m")
        XCTAssertEqual(responses["instructions"] as? String, "指令"); XCTAssertEqual(responses["input"] as? String, "原文")
        XCTAssertEqual(responses["store"] as? Bool, false)
    }

    func testAnswersAreReadFromAllThreeInterfaceFormats() throws {
        func data(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object) }
        XCTAssertEqual(try AIClient.parseText(try data(["choices": [["message": ["content": " 好 "], "finish_reason": "stop"]]]), style: .chatCompletions), "好")
        XCTAssertEqual(try AIClient.parseText(try data(["content": [["type": "thinking", "thinking": "…"], ["type": "text", "text": "好"]], "stop_reason": "end_turn"]),
                                              style: .anthropicMessages), "好")
        XCTAssertEqual(try AIClient.parseText(try data(["status": "completed", "output": [["type": "message", "content": [["type": "output_text", "text": "好"]]]]]),
                                              style: .responses), "好")
        XCTAssertThrowsError(try AIClient.parseText(try data(["choices": [["message": ["content": "半句"], "finish_reason": "length"]]]), style: .chatCompletions)) {
            XCTAssertEqual($0 as? AIRequestError, .incompleteResponse)
        }
        XCTAssertThrowsError(try AIClient.parseText(try data(["choices": [["message": ["content": ""], "finish_reason": "stop"]]]), style: .chatCompletions))
        XCTAssertThrowsError(try AIClient.parseText(Data("not json".utf8), style: .responses))
    }

    func testStubbedProviderAnswersEachInterfaceFormat() throws {
        let body = try JSONSerialization.data(withJSONObject: ["model": "m", "messages": [["role": "system", "content": AIDrafting.checklistInstructions],
                                                                                         ["role": "user", "content": "备忘"]]])
        for (path, style) in [("/v1/chat/completions", APIStyle.chatCompletions), ("/v1/messages", .anthropicMessages), ("/v1/responses", .responses)] {
            let (status, data) = AIQAStubProtocol.response(path: path, body: body)
            XCTAssertEqual(status, 200)
            XCTAssertEqual(try AIDrafting.checklist(from: try AIClient.parseText(data, style: style)).count, 4, path)
        }
    }
}
