import Foundation

/// Rules worked out on the device from the user's own data. They never ask a
/// model and do not depend on AI 整理 being on. Pure, so both ends replay the
/// same samples in tests.
enum MojiLocalRules {
    // MARK: 预计时长默认值（同类历史用时）

    static let typicalWindowDays = 60
    static let typicalSampleLimit = 10
    static let typicalMinimumSamples = 3

    /// The usual length of a plan of this type: plans of the type finished in
    /// the last 60 days that have time records, the 10 finished most recently,
    /// at least 3 of them. Their median, rounded to 5 minutes, kept within
    /// 5…240. Nil when there is not enough to go on.
    static func typicalMinutes(for category: RecordCategory, snapshot: PlanSnapshot, now: Date = Date(),
                               calendar: Calendar = .current) -> Int? {
        guard let windowStart = calendar.date(byAdding: .day, value: -typicalWindowDays, to: now) else { return nil }
        var seconds: [UUID: Double] = [:]
        for record in snapshot.records {
            guard let id = record.checkInItemID else { continue }
            seconds[id, default: 0] += max(0, record.endDate.timeIntervalSince(record.startDate))
        }
        let samples = snapshot.checkInItems.compactMap { plan -> (finished: Date, minutes: Double)? in
            guard plan.kind == .planned, plan.category == category, plan.status == .completed,
                  let finished = plan.completedAt ?? plan.actualEndDate,
                  finished >= windowStart, finished <= now,
                  let total = seconds[plan.id], total > 0 else { return nil }
            return (finished, total / 60)
        }
        .sorted { $0.finished > $1.finished }
        .prefix(typicalSampleLimit)
        .map(\.minutes)
        .sorted()
        guard samples.count >= typicalMinimumSamples else { return nil }
        let middle = samples.count / 2
        let median = samples.count.isMultiple(of: 2) ? (samples[middle - 1] + samples[middle]) / 2 : samples[middle]
        let rounded = Int((median / 5).rounded()) * 5
        return min(240, max(5, rounded))
    }

    /// 按以往的学习计划，约 45 分钟
    static func typicalDurationNote(category: RecordCategory, minutes: Int) -> String {
        "按以往的\(category.displayName)计划，约 \(minutes) 分钟"
    }

    // MARK: 回顾页「下一步」

    /// The open plan of today's list carried over the longest; on a tie the
    /// one higher in the list. Nil when nothing has been carried.
    static func longestCarried(_ pending: [CheckInItem], today: Date,
                               calendar: Calendar = .mojiISO) -> (item: CheckInItem, days: Int)? {
        var best: (item: CheckInItem, days: Int)?
        for item in pending where item.kind == .planned && item.status == .planned {
            guard let days = CarryOverSeal.daysLate(scheduledStart: item.scheduledStart, on: today, calendar: calendar),
                  days >= 1 else { continue }
            if best == nil || days > best!.days { best = (item, days) }
        }
        return best
    }

    /// 下一步：先做「归纳昨日待办」（顺延 3 天）
    static func carriedNextStep(title: String, days: Int) -> String {
        "下一步：先做「\(AIDailyBrief.shortTitle(title))」（顺延 \(days) 天）"
    }

    // MARK: 存为模板提示

    /// How a new plan came to be. Only plans the user wrote by hand count.
    enum PlanEntrySource {
        case typed, template, draft, recurrence, copy
    }

    /// Titles compared without outer spaces, with runs of spaces as one and
    /// Latin letters in either case.
    static func titleKey(_ title: String) -> String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .lowercased()
    }

    /// How many times this title has been written, counting the new plan, when
    /// it is worth offering to keep it as a template: at least two other plans
    /// with the same title written in the last 60 days (archived ones too), no
    /// template with that title yet, and the title not waved away before.
    static func templateHintCount(for plan: CheckInItem, source: PlanEntrySource, snapshot: PlanSnapshot,
                                  dismissed: Set<String>, now: Date = Date(),
                                  calendar: Calendar = .current) -> Int? {
        guard source == .typed, plan.kind == .planned else { return nil }
        let key = titleKey(plan.title)
        guard !key.isEmpty, !dismissed.contains(key),
              !snapshot.planTemplates.contains(where: { titleKey($0.title) == key }),
              let windowStart = calendar.date(byAdding: .day, value: -typicalWindowDays, to: now) else { return nil }
        let sameTitle = snapshot.checkInItems.filter {
            $0.id != plan.id && $0.kind == .planned && titleKey($0.title) == key
        }
        // A repeating plan already brings it back by itself: no template to suggest.
        guard !sameTitle.contains(where: { ($0.repeatRule ?? .never) != .never }) else { return nil }
        // Occurrences a repeat made were not written by hand.
        let others = sameTitle.filter { $0.createdAt >= windowStart && $0.generatedFromOccurrenceID == nil }.count
        return others >= 2 ? others + 1 : nil
    }

    /// 这件事写过 3 次了，存成模板，下次一点就有
    static func templateHintText(count: Int) -> String {
        "这件事写过 \(count) 次了，存成模板，下次一点就有"
    }

    // MARK: 倒数日图标

    /// Checked in order; the first group with a word in the title wins.
    static let countdownSymbolRules: [(words: [String], symbol: String)] = [
        (["生日", "寿"], "gift"),
        (["考试", "考研", "笔试", "测验", "期末", "期中"], "pencil.and.outline"),
        (["面试"], "person.2"),
        (["旅行", "旅游", "出发", "航班", "机票", "出国"], "airplane"),
        (["交付", "上线", "发布", "截止", "提交", "ddl"], "flag.fill"),
        (["纪念", "结婚", "恋爱", "在一起"], "heart.fill"),
        (["毕业"], "graduationcap"),
        (["开学", "入学", "报到"], "book"),
        (["搬家"], "house"),
        (["体检", "复查", "医院", "手术"], "cross.case"),
        (["演唱会", "音乐会", "演出"], "music.note"),
        (["比赛", "马拉松", "决赛"], "figure.run"),
        (["春节", "过年", "新年", "元旦"], "fireworks")
    ]

    /// The defaults of 倒数日 and 纪念日: a day still wearing one follows its title.
    static let countdownDefaultSymbols: Set<String> = ["hourglass", "seal"]

    static func countdownSymbol(for title: String) -> String? {
        let text = title.lowercased()
        return countdownSymbolRules.first { rule in rule.words.contains { text.contains($0) } }?.symbol
    }

    static func countdownSymbol(title: String, fallback: String) -> String {
        countdownSymbol(for: title) ?? fallback
    }
}

/// The memo editor's text when a checklist keeps the words it was made from.
enum MemoTextRules {
    /// What is saved. A checklist keeps its 原文 in `content`.
    static func normalized(title: String, content: String, mode: MemoMode) -> (title: String, content: String) {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        if mode == .checklist { return (cleanTitle, cleanContent) }
        guard cleanTitle.isEmpty else { return (cleanTitle, cleanContent) }
        let lines = cleanContent.components(separatedBy: .newlines)
        let inferredTitle = lines.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let remaining = lines.dropFirst().joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return (inferredTitle, remaining)
    }

    /// 转为正文 by hand: the items as lines, and the 原文 after a blank line.
    static func noteText(from items: [MemoChecklistItem], original: String) -> String {
        let lines = items.map { "\($0.isCompleted ? "✓" : "•") \($0.text)" }.joined(separator: "\n")
        let kept = original.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kept.isEmpty else { return lines }
        return lines.isEmpty ? kept : lines + "\n\n" + kept
    }

    /// 转回正文: the checklist goes, the 原文 is the text again.
    static func backToText(original: String) -> (mode: MemoMode, content: String, items: [MemoChecklistItem]) {
        (.note, original, [])
    }
}

/// Titles the user waved the template hint away for. App-local defaults:
/// not in the snapshot, not in backups.
enum TemplateHintStore {
    static let key = "moji.templateHint.dismissed.v1"
    static let limit = 300

    static var defaults: UserDefaults {
#if DEBUG
        if !AIQAFixture.scenario.isEmpty, let suite = UserDefaults(suiteName: "moji.qa.templateHint") { return suite }
#endif
        return .standard
    }

#if DEBUG
    /// Screens photographed for QA start from nothing every launch.
    static func resetForQA() {
        guard !AIQAFixture.scenario.isEmpty else { return }
        UserDefaults(suiteName: "moji.qa.templateHint")?.removePersistentDomain(forName: "moji.qa.templateHint")
    }
#endif

    static func dismissed(in defaults: UserDefaults = TemplateHintStore.defaults) -> Set<String> {
        Set(defaults.stringArray(forKey: key) ?? [])
    }

    static func dismiss(_ title: String, in defaults: UserDefaults = TemplateHintStore.defaults) {
        let titleKey = MojiLocalRules.titleKey(title)
        var list = defaults.stringArray(forKey: key) ?? []
        guard !titleKey.isEmpty, !list.contains(titleKey) else { return }
        list.append(titleKey)
        if list.count > limit { list.removeFirst(list.count - limit) }
        defaults.set(list, forKey: key)
    }
}
