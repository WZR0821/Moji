import XCTest
import UniformTypeIdentifiers
@testable import Moji

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
