import Foundation

/// Everything Moji asks a model for, one function per place it is used. Each
/// function sends one request and returns a draft: nothing here writes to the
/// plan store.
@MainActor
struct AIAssistant {
    let store: AISettingsStore
    var client = AIClient()

    static var shared: AIAssistant { AIAssistant(store: .shared) }

    /// The types a draft may use: the built-in ones and the user's own.
    nonisolated static func categories() -> [RecordCategory] {
        PlanCategoryLibrary.availableCategories(
            customJSON: SharedPersistence.sharedDefaults.string(forKey: PlanSettingsKeys.customCategories) ?? "[]")
    }

    nonisolated static var defaultCategory: RecordCategory {
        RecordCategory(rawValue: SharedPersistence.sharedDefaults.string(forKey: PlanSettingsKeys.defaultCategory)
                       ?? RecordCategory.study.rawValue) ?? .study
    }

    private func ask(_ instructions: String, _ input: String, timeout: TimeInterval = 40) async throws -> String {
        let clean = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { throw AIRequestError.emptyText }
        guard clean.count <= AIDrafting.maximumInputCharacters else { throw AIRequestError.tooMuchText }
        let credentials = try store.credentials()
        return try await client.complete(instructions: instructions, input: clean, entryID: credentials.entryID,
                                         configuration: credentials.configuration, apiKey: credentials.apiKey,
                                         timeout: timeout)
    }

    /// The time, length, type and repeat written into one typed plan.
    func readEntry(_ text: String, categories: [RecordCategory] = AIAssistant.categories(),
                   now: Date = Date()) async throws -> EntrySuggestion {
        let answer = try await ask(AIDrafting.entryInstructions(now: now, categories: categories), text, timeout: 20)
        return try AIDrafting.entrySuggestion(from: answer, original: text, now: now, categories: categories)
    }

    /// 今日提要: the order of today's plans and why the first one goes first.
    func dailyBrief(pending: [CheckInItem], countdowns: [CountdownEvent],
                    now: Date = Date()) async throws -> (order: [Int], reason: String?) {
        let plans = Array(pending.prefix(AIDailyBrief.maximumPlans))
        let answer = try await ask(AIDailyBrief.instructions(now: now),
                                   AIDailyBrief.input(pending: plans, countdowns: countdowns, today: now), timeout: 25)
        return try AIDailyBrief.order(from: answer, count: plans.count)
    }

    /// 晚间收尾: what to do with each plan left this evening.
    func eveningWrap(done: [CheckInItem], pending: [CheckInItem], countdowns: [CountdownEvent],
                     now: Date = Date()) async throws -> [Int: WrapArrangement] {
        let plans = Array(pending.prefix(AIEveningWrap.maximumPlans))
        let answer = try await ask(AIEveningWrap.instructions(now: now),
                                   AIEveningWrap.input(done: done, pending: plans, countdowns: countdowns, today: now),
                                   timeout: 25)
        return try AIEveningWrap.arrangement(from: answer, count: plans.count)
    }

    /// 本周来信, from the same record of the week that 周复盘 is drafted from.
    func weeklyLetter(snapshot: PlanSnapshot, week: Date, now: Date = Date()) async throws -> AIWeeklyLetter.Draft {
        let record = AIDrafting.weekRecord(snapshot: snapshot, week: week, now: now)
        guard !record.plans.isEmpty else { throw AIRequestError.emptyText }
        return try AIWeeklyLetter.letter(from: try await ask(AIWeeklyLetter.instructions, record.text),
                                         plans: record.plans)
    }

    func breakDown(_ plan: CheckInItem) async throws -> [PlanDraft] {
        let answer = try await ask(AIDrafting.breakdownInstructions, AIDrafting.breakdownInput(plan))
        return try AIDrafting.steps(from: answer, plan: plan)
    }

    func checklist(title: String, content: String) async throws -> [String] {
        let input = title.isEmpty ? content : "标题：\(title)\n\(content)"
        return try AIDrafting.checklist(from: try await ask(AIDrafting.checklistInstructions, input))
    }

    func conversionHints(memoTitle: String, rows: [String],
                         categories: [RecordCategory] = AIAssistant.categories(),
                         now: Date = Date()) async throws -> [Int: ConversionHint] {
        let answer = try await ask(AIDrafting.conversionInstructions(now: now, categories: categories),
                                   AIDrafting.conversionInput(memoTitle: memoTitle, rows: rows), timeout: 25)
        return try AIDrafting.conversionHints(from: answer, rowCount: rows.count, now: now, categories: categories)
    }

    func draftReflection(snapshot: PlanSnapshot, week: Date, now: Date = Date()) async throws -> ReflectionDraft {
        let record = AIDrafting.weekRecord(snapshot: snapshot, week: week, now: now)
        guard !record.plans.isEmpty else { throw AIRequestError.emptyText }
        return try AIDrafting.reflection(from: try await ask(AIDrafting.reflectionInstructions, record.text),
                                         plans: record.plans)
    }

    func draftNextWeek(snapshot: PlanSnapshot, week: Date,
                       categories: [RecordCategory] = AIAssistant.categories(),
                       now: Date = Date()) async throws -> [PlanDraft] {
        let answer = try await ask(AIDrafting.nextWeekInstructions(categories: categories),
                                   AIDrafting.nextWeekInput(snapshot: snapshot, week: week, now: now))
        return try AIDrafting.nextWeekDrafts(from: answer, week: week, fallbackCategory: Self.defaultCategory,
                                             categories: categories)
    }

    func draftPreparation(for event: CountdownEvent, categories: [RecordCategory] = AIAssistant.categories(),
                          now: Date = Date(), calendar: Calendar = .current) async throws -> [PlanDraft] {
        let today = calendar.startOfDay(for: now)
        // A repeating day is prepared for at its next occurrence, not its origin.
        let target = calendar.startOfDay(for: event.occurrenceDate(relativeTo: now, calendar: calendar))
        let daysLeft = max(0, calendar.dateComponents([.day], from: today, to: target).day ?? 0)
        let answer = try await ask(AIDrafting.preparationInstructions(daysLeft: daysLeft, categories: categories),
                                   AIDrafting.preparationInput(title: event.title, target: target, daysLeft: daysLeft, calendar: calendar))
        return try AIDrafting.preparationDrafts(from: answer, target: target, now: now,
                                                fallbackCategory: Self.defaultCategory, categories: categories,
                                                calendar: calendar)
    }

    func libraryFilter(_ text: String, categories: [RecordCategory] = AIAssistant.categories(),
                       now: Date = Date()) async throws -> LibraryFilter {
        let answer = try await ask(AIDrafting.filterInstructions(now: now, categories: categories), text, timeout: 20)
        return try AIDrafting.libraryFilter(from: answer, now: now, categories: categories)
    }

    func importPlans(_ text: String, categories: [RecordCategory] = AIAssistant.categories(),
                     now: Date = Date()) async throws -> [PlanDraft] {
        let answer = try await ask(AIDrafting.importInstructions(now: now, categories: categories), text)
        return try AIDrafting.importDrafts(from: answer, now: now, fallbackCategory: Self.defaultCategory,
                                           categories: categories)
    }
}

extension Error {
    /// The sentence shown under a draft that could not be made.
    var draftMessage: String {
        if self is CancellationError { return "已取消。" }
        return (self as? LocalizedError)?.errorDescription ?? "没有完成，请稍后再试。"
    }
}
