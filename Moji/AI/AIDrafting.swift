import Foundation

/// A plan the model proposed. Nothing is saved until the user accepts it.
struct PlanDraft: Identifiable, Equatable {
    var id = UUID()
    var title: String
    /// Start of the day the plan would land on.
    var day: Date
    var scheduleKind: ScheduleKind = .allDay
    var hour = 0
    var minute = 0
    var minutes: Int? = nil
    var category: RecordCategory
    /// Why the model proposed it; shown under the row, never saved.
    var reason = ""
    var isSelected = true

    /// `fallbackMinutes` is kept as the plan's length when the draft has none:
    /// the usual length of its type (it stays switched off, as before).
    func plan(note: String = "", fallbackMinutes: Int? = nil, calendar: Calendar = .current) -> CheckInItem {
        var components = calendar.dateComponents([.era, .year, .month, .day], from: day)
        components.hour = scheduleKind.representativeHour ?? min(23, max(0, hour))
        components.minute = scheduleKind == .exactTime ? min(59, max(0, minute)) : 0
        components.second = 0
        return CheckInItem(
            title: title,
            category: category,
            scheduledStart: calendar.date(from: components) ?? day,
            plannedMinutes: minutes ?? fallbackMinutes ?? 25,
            note: note,
            scheduleKind: scheduleKind,
            plannedDurationEnabled: minutes != nil
        )
    }

    var scheduleText: String {
        scheduleKind == .exactTime ? String(format: "%02d:%02d", hour, minute) : scheduleKind.displayName
    }

    func metaText(calendar: Calendar = .current, now: Date = Date()) -> String {
        var parts = [AIDrafting.dayText(day, now: now, calendar: calendar)]
        if scheduleKind != .allDay { parts.append(scheduleText) }
        if let minutes { parts.append(DurationText.full(minutes: minutes)) }
        parts.append(category.displayName)
        return parts.joined(separator: " · ")
    }
}

/// What was read out of one typed plan. Every field is optional: a field the
/// sentence did not mention stays nil and is left alone.
struct EntrySuggestion: Equatable {
    var title: String?
    var day: Date?
    var scheduleKind: ScheduleKind?
    var hour = 0
    var minute = 0
    var minutes: Int?
    var category: RecordCategory?
    var repeatRule: PlanRepeatRule?
    /// Two or more when the sentence holds several separate things.
    var parts: [String] = []

    var hasDetails: Bool {
        day != nil || scheduleKind != nil || minutes != nil || category != nil || repeatRule != nil
    }

    var isEmpty: Bool { !hasDetails && parts.count < 2 }

    /// The words on the suggestion strip, in reading order.
    func chips(now: Date = Date(), calendar: Calendar = .current) -> [String] {
        var list: [String] = []
        if let day { list.append(AIDrafting.dayText(day, now: now, calendar: calendar)) }
        if let scheduleKind {
            list.append(scheduleKind == .exactTime ? String(format: "%02d:%02d", hour, minute) : scheduleKind.displayName)
        }
        if let minutes { list.append(DurationText.full(minutes: minutes)) }
        if let category { list.append(category.displayName) }
        if let repeatRule { list.append(repeatRule.displayName) }
        return list
    }

    /// The plan with every recognised field applied; nil when nothing changes.
    func applied(to original: CheckInItem, calendar: Calendar = .current) -> CheckInItem? {
        var item = original
        var changes: [PlanQuickChange] = []
        if let title, !title.isEmpty, title != original.title {
            changes.append(.content(title: title, note: original.note))
        }
        if let day { changes.append(.date(day)) }
        if scheduleKind != nil || minutes != nil {
            // A length on its own keeps the time the plan already has.
            let time = calendar.dateComponents([.hour, .minute], from: original.scheduledStart)
            changes.append(.schedule(scheduleKind ?? original.effectiveScheduleKind,
                                     hour: scheduleKind == nil ? time.hour ?? 0 : hour,
                                     minute: scheduleKind == nil ? time.minute ?? 0 : minute,
                                     estimatedMinutes: minutes ?? original.plannedDurationMinutes))
        }
        if let category { changes.append(.category(category)) }
        if let repeatRule { changes.append(.recurrence(repeatRule, weekdays: [])) }
        for change in changes {
            guard let next = change.applying(to: item, calendar: calendar) else { continue }
            item = next
        }
        return item == original ? nil : item
    }

    /// The suggestion without what the plan already says, so the strip only
    /// offers changes. The chips the user adopted before adding are already
    /// on the plan, and the model reads the same words again.
    func removingMatches(of plan: CheckInItem, calendar: Calendar = .current) -> EntrySuggestion {
        var value = self
        if let day, calendar.isDate(day, inSameDayAs: plan.scheduledStart) { value.day = nil }
        if let kind = scheduleKind, kind == plan.effectiveScheduleKind {
            let time = calendar.dateComponents([.hour, .minute], from: plan.scheduledStart)
            if kind != .exactTime || (time.hour == hour && time.minute == minute) { value.scheduleKind = nil }
        }
        if let minutes, minutes == plan.plannedDurationMinutes { value.minutes = nil }
        if let category, category == plan.category { value.category = nil }
        if let repeatRule, repeatRule == plan.effectiveRepeatRule { value.repeatRule = nil }
        if let title, title == plan.title { value.title = nil }
        return value
    }

    /// 拆成 N 条: the first part takes the plan's place, the rest are new
    /// plans; every field the suggestion read besides the title goes on all
    /// of them. Nil when there is nothing to split.
    func split(_ plan: CheckInItem, origin: String? = nil, calendar: Calendar = .current) -> (first: CheckInItem, rest: [CheckInItem])? {
        guard parts.count >= 2, plan.kind == .planned else { return nil }
        var fields = self
        fields.title = nil
        fields.parts = []
        var first = plan
        first.title = parts[0]
        first = fields.applied(to: first, calendar: calendar) ?? first
        let rest = parts.dropFirst().map { part -> CheckInItem in
            var item = CheckInItem(
                title: part,
                category: plan.category,
                scheduledStart: plan.scheduledStart,
                plannedMinutes: plan.plannedMinutes,
                scheduleKind: plan.effectiveScheduleKind,
                detailsConfigured: plan.hasConfiguredDetails,
                plannedDurationEnabled: plan.plannedDurationEnabled
            )
            item = fields.applied(to: item, calendar: calendar) ?? item
            item.aiOrigin = origin
            return item
        }
        return (first, Array(rest))
    }
}

/// What a plan said when a suggestion was made for it. Once any of these is
/// changed the suggestion is stale and is dropped.
struct EntrySuggestionBasis: Equatable {
    var title: String
    var status: CheckInStatus
    var day: Date
    var scheduleKind: ScheduleKind
    var hour: Int
    var minute: Int
    var minutes: Int?
    var category: RecordCategory
    var repeatRule: PlanRepeatRule

    init(_ plan: CheckInItem, calendar: Calendar = .current) {
        title = plan.title
        status = plan.status
        day = calendar.startOfDay(for: plan.scheduledStart)
        scheduleKind = plan.effectiveScheduleKind
        let time = calendar.dateComponents([.hour, .minute], from: plan.scheduledStart)
        hour = scheduleKind == .exactTime ? time.hour ?? 0 : 0
        minute = scheduleKind == .exactTime ? time.minute ?? 0 : 0
        minutes = plan.plannedDurationMinutes
        category = plan.category
        repeatRule = plan.effectiveRepeatRule
    }

    func stillHolds(for plan: CheckInItem, calendar: Calendar = .current) -> Bool {
        EntrySuggestionBasis(plan, calendar: calendar) == self
    }
}

/// A suggestion kept under a row: where it came from and what the plan said then.
struct ShownEntrySuggestion: Equatable {
    var suggestion: EntrySuggestion
    var basis: EntrySuggestionBasis
    /// Read on the device; a model's reading may still replace it.
    var isLocal: Bool
}

struct ReflectionDraft: Equatable {
    struct Evidence: Equatable, Identifiable {
        var id = UUID()
        var point: String
        /// Titles of the plans the point rests on, looked up locally.
        var planTitles: [String]
        /// The same plans, for opening them under the point. Empty for a letter's points.
        var planIDs: [UUID] = []
        static func == (lhs: Evidence, rhs: Evidence) -> Bool {
            lhs.point == rhs.point && lhs.planTitles == rhs.planTitles
        }
    }
    var note: String
    var nextFocus: String
    var evidence: [Evidence] = []
}

struct ConversionHint: Equatable {
    var day: Date?
    var category: RecordCategory?
    func text(now: Date = Date(), calendar: Calendar = .current) -> String {
        [day.map { AIDrafting.dayText($0, now: now, calendar: calendar) }, category?.displayName]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// The three pickers and the search word of the plan library.
struct LibraryFilter: Equatable {
    var keyword = ""
    var category = "all"
    var status = "all"
    var period = "all"
    var from: Date?
    var to: Date?
}

/// Plans a draft added or changed, and memos it created: one step of undo.
struct PlanDraftUndo {
    var added: [CheckInItem]
    var changed: PlanWorkflowUndo
    var addedMemos: [MemoItem] = []

    var total: Int { added.count + changed.before.count + addedMemos.count }

    /// Takes back only what has not been touched since. A plan that was edited,
    /// started or finished after the draft was accepted stays as it is, and so
    /// does a memo edited since.
    @discardableResult
    func apply(to state: inout PlanSnapshot, blockedID: UUID? = nil) -> Int {
        var count = changed.apply(to: &state, blockedID: blockedID)
        for plan in added {
            guard let index = state.checkInItems.firstIndex(where: { $0.id == plan.id }),
                  state.checkInItems[index] == plan,
                  state.activeSession?.checkInItemID != plan.id,
                  plan.id != blockedID else { continue }
            state.checkInItems.remove(at: index)
            count += 1
        }
        for memo in addedMemos {
            guard let index = state.memos.firstIndex(where: { $0.id == memo.id }), state.memos[index] == memo else { continue }
            state.memos.remove(at: index)
            count += 1
        }
        return count
    }
}

/// A draft checked on the device and found not to hold.
enum AIDraftCheckError: LocalizedError, Equatable {
    /// Every sentence of a reflection named a number the week's record does not have.
    case reflectionMismatch
    /// The week has no plans, so there is nothing to draft from.
    case emptyWeek

    var errorDescription: String? {
        switch self {
        case .reflectionMismatch: return "这次起草的内容和本周记录对不上，再试一次。"
        case .emptyWeek: return "这一周还没有计划记录，没有可以拟的内容。"
        }
    }
}

/// The numbers of one week, worked out on the device: the same ones the
/// record sent to the model says, and the line shown under a drafted reflection.
struct WeekRecordStats: Equatable {
    /// 完成、没做完、跳过 together: every plan the record lists.
    var planned: Int
    var completed: Int
    var open: Int
    var skipped: Int
    var actualMinutes: Int

    /// 本周排了 6 件，完成 3 件，没做完 2 件；专注 1 小时 20 分钟
    var line: String {
        "本周排了 \(planned) 件，完成 \(completed) 件，没做完 \(open) 件；专注 \(DurationText.full(minutes: actualMinutes))"
    }
}

/// Instructions sent to the model and the reading of its answers. Everything
/// here is pure, so the same Chinese samples can be replayed in tests.
enum AIDrafting {
    static let maximumInputCharacters = 6_000

    // MARK: Shared wording

    static let preface = """
    你是“墨记”（一个计划与备忘 App）里的整理助手。只输出一个 JSON 对象，不要解释，不要用代码块包裹。\
    文字字段使用用户原文的语言。不要编造用户没有写的事情。
    """

    static func todayLine(_ now: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        let names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
        let weekday = (calendar.component(.weekday, from: now) + 5) % 7
        return String(format: "今天是 %04d-%02d-%02d（%@）。", c.year ?? 2000, c.month ?? 1, c.day ?? 1, names[weekday])
    }

    static let dateFields = """
    日期用下面三组字段之一表示，其余填 null；原文没有时间线索时三组都填 null：
    "date"："YYYY-MM-DD"，原文写了明确日期（如 12月5日）时用；
    "offset_days"：整数，今天=0、明天=1、后天=2、三天后=3；
    "weekday"：1–7（周一=1…周日=7），配合 "week_offset"：0=本周、1=下周、2=下下周（没说“下周”就填 0）。
    """

    static func categoryLine(_ categories: [RecordCategory]) -> String {
        "类型只能从这些里选一个，拿不准就填 null：" + categories.map(\.displayName).joined(separator: "、") + "。"
    }

    // MARK: Instructions

    static func entryInstructions(now: Date, categories: [RecordCategory], calendar: Calendar = .current) -> String {
        """
        \(preface)
        任务：把用户写下的一句计划拆出结构。\(todayLine(now, calendar: calendar))
        输出 JSON：
        {"title": 去掉日期、钟点、时长之后的事情本身，尽量保留原话,
         "date": …, "offset_days": …, "weekday": …, "week_offset": …,
         "part": "morning"|"afternoon"|"evening"|"exact"|null（exact 表示原话写了具体钟点）,
         "time": "HH:mm"|null（24 小时制，只在 part 为 exact 时填）,
         "minutes": 整数|null（原话写了要花多久才填，单位分钟）,
         "category": 类型名|null,
         "repeat": "daily"|"weekdays"|"weekly"|"monthly"|null,
         "items": 字符串数组（只有原话明显是几件互不相同的事时才逐件列出，每件保留原话；否则给空数组）}
        \(dateFields)
        \(categoryLine(categories))
        原话没有提到的字段一律填 null，不要猜。
        """
    }

    static let breakdownInstructions = """
    \(preface)
    任务：把一件计划拆成可以直接动手的小步骤。3 到 6 步，每步一句话、以动词开头、不超过 20 个字；\
    第一步要小到十分钟内能开始。不要加入原计划没有涉及的目标。
    输出 JSON：{"steps":[{"title":"…","minutes":整数|null}]}
    """

    static let checklistInstructions = """
    \(preface)
    任务：把一则备忘里要做的事整理成清单。每项一句话，保留原文里的名词和数字；\
    感想、背景这类不是待办的内容不要列入；不要添加原文没有的事。最多 20 项。
    输出 JSON：{"items":["…"]}
    """

    static func conversionInstructions(now: Date, categories: [RecordCategory], calendar: Calendar = .current) -> String {
        """
        \(preface)
        任务：下面是一则备忘里准备转成计划的几行，每行前有编号。为每一行判断计划日期和类型。\(todayLine(now, calendar: calendar))
        输出 JSON：{"rows":[{"index":编号,"date":…,"offset_days":…,"weekday":…,"week_offset":…,"category":类型名|null}]}
        \(dateFields)
        \(categoryLine(categories))
        """
    }

    static let reflectionInstructions = """
    \(preface)
    任务：根据下面这份一周记录，替用户起草周复盘。只依据记录里的事实；数字只能照抄记录，不要推算新的数字；\
    不评价用户的为人。语气平实，像用户写给自己的话，不用“你”。
    输出 JSON：
    {"note": 本周复盘，2 到 4 句，写做得好的事和遇到的问题,
     "next_focus": 给下周的一句提醒，不超过 40 字,
     "evidence":[{"point":"一句话结论","plans":[所依据的计划编号]}]}
    evidence 给 1 到 3 条，编号只能用记录里出现过的。
    """

    static func nextWeekInstructions(categories: [RecordCategory]) -> String {
        """
        \(preface)
        任务：根据下面的记录，替用户拟下周的计划草稿，3 到 6 条。优先安排没做完的事（太大的可以拆小）\
        和“下周关注”里提到的方向；记录里完全没有线索的新目标不要安排。
        输出 JSON：{"plans":[{"title":"…","weekday":1–7（周一=1）,"part":"morning"|"afternoon"|"evening"|null,\
        "minutes":整数|null,"category":类型名|null,"reason":"为什么安排它，不超过 20 字"}]}
        \(categoryLine(categories))
        """
    }

    static func preparationInstructions(daysLeft: Int, categories: [RecordCategory]) -> String {
        """
        \(preface)
        任务：用户有一个重要的日子。替它拟一份准备计划，3 到 6 条，由远到近，每条是一件具体可做的事。\
        只围绕这个日子本身，不要臆测用户没有提到的细节（比如具体科目、地点、人名）。
        输出 JSON：{"plans":[{"title":"…","days_before":整数（提前几天做，0 表示当天，不能超过 \(daysLeft)）,\
        "minutes":整数|null,"category":类型名|null,"reason":"不超过 20 字"}]}
        \(categoryLine(categories))
        """
    }

    static func filterInstructions(now: Date, categories: [RecordCategory], calendar: Calendar = .current) -> String {
        """
        \(preface)
        任务：把用户的一句话变成计划库的筛选条件。\(todayLine(now, calendar: calendar))
        输出 JSON：
        {"keyword": 要在标题和说明里搜索的词|null（只在原话点名了某件事或某个词时填）,
         "category": 类型名|null,
         "status": "all"|"planned"|"inProgress"|"completed"|"skipped"|"archived",
         "period": "all"|"past"|"today"|"future"|"this_week"|"last_week"|"next_week"|"this_month"|"last_month"|"range",
         "from": "YYYY-MM-DD"|null, "to": "YYYY-MM-DD"|null}
        “没做完”“待办”是 planned，“做完的”是 completed。只有原话给了明确起止日期时 period 才用 range 并填 from 和 to。
        \(categoryLine(categories))
        """
    }

    static func importInstructions(now: Date, categories: [RecordCategory], calendar: Calendar = .current) -> String {
        """
        \(preface)
        任务：从用户粘贴的一段文字里找出要做的事，整理成计划草稿。每件事一条，标题保留原文的名词和数字、不超过 30 字；\
        不是待办的内容不要列入；不要添加原文没有的事。最多 12 条。\(todayLine(now, calendar: calendar))
        输出 JSON：{"plans":[{"title":"…","date":…,"offset_days":…,"weekday":…,"week_offset":…,\
        "part":"morning"|"afternoon"|"evening"|"exact"|null,"time":"HH:mm"|null,"minutes":整数|null,"category":类型名|null}]}
        \(dateFields)
        \(categoryLine(categories))
        原文没有提到的字段填 null。
        """
    }

    // MARK: Local judgement

    /// A short plain title has nothing to read; asking would only send it out.
    static func isWorthReading(_ text: String) -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 3, clean.count <= 200 else { return false }
        if clean.rangeOfCharacter(from: .decimalDigits) != nil { return true }
        let cues = ["今天", "明天", "后天", "今晚", "明早", "明晚", "周", "星期", "礼拜", "月", "号", "早上", "上午",
                    "中午", "下午", "晚上", "点", "半", "分钟", "小时", "每天", "每周", "每月", "每个", "工作日",
                    "、", "，", ",", "；", ";", "然后", "还有", "以及", "和"]
        return cues.contains { clean.contains($0) }
    }

    /// A search word is a word; a sentence is something to turn into filters.
    static func looksLikeFilterSentence(_ text: String) -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 4, clean.count <= 80 else { return false }
        let cues = ["没做", "做完", "完成", "待办", "跳过", "归档", "上周", "这周", "本周", "下周", "上个月", "这个月",
                    "本月", "今天", "过去", "以前", "之前", "未来", "以后", "之后", "的计划", "所有", "全部", "哪些"]
        return cues.contains { clean.contains($0) }
    }

    /// 11月18日 周三
    static func monthDayWeekday(_ day: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.month, .day], from: day)
        let names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
        let weekday = (calendar.component(.weekday, from: day) + 5) % 7
        return "\(c.month ?? 1)月\(c.day ?? 1)日 \(names[weekday])"
    }

    /// One plan opened under a point of a drafted reflection:
    /// 背单词 · 11月18日 周三 · 已完成
    static func evidencePlanLine(_ plan: CheckInItem, now: Date, calendar: Calendar = .current) -> String {
        let state: String
        switch plan.status {
        case .completed: state = "已完成"
        case .skipped: state = "已跳过"
        case .planned, .inProgress:
            if let days = CarryOverSeal.daysLate(scheduledStart: plan.scheduledStart, on: now, calendar: calendar) {
                state = "顺延 \(days) 天"
            } else {
                state = "没做完"
            }
        }
        return "\(plan.title) · \(monthDayWeekday(plan.scheduledStart, calendar: calendar)) · \(state)"
    }

    static func dayText(_ day: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        let start = calendar.startOfDay(for: day)
        let today = calendar.startOfDay(for: now)
        switch calendar.dateComponents([.day], from: today, to: start).day ?? 0 {
        case 0: return "今天"
        case 1: return "明天"
        case 2: return "后天"
        default:
            let c = calendar.dateComponents([.month, .day], from: start)
            let names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
            let weekday = (calendar.component(.weekday, from: start) + 5) % 7
            return "\(c.month ?? 1)月\(c.day ?? 1)日 \(names[weekday])"
        }
    }

    // MARK: Reading answers

    /// The JSON object in a model's answer, tolerating a code fence or a
    /// sentence around it.
    static func object(in answer: String) throws -> [String: Any] {
        guard let start = answer.firstIndex(of: "{"), let end = answer.lastIndex(of: "}"), start < end,
              let data = String(answer[start...end]).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AIRequestError.unreadableAnswer
        }
        return object
    }

    static func string(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
    }

    static func integer(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            // JSON true and false arrive as numbers too. `value is Bool` cannot
            // tell them from 0 and 1, which would lose “明天” and row 0.
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            let amount = number.doubleValue
            guard amount.isFinite, abs(amount) < 1_000_000 else { return nil }
            return Int(amount.rounded())
        }
        if let text = value as? String { return Int(text.trimmingCharacters(in: .whitespaces)) }
        return nil
    }

    static func category(_ value: Any?, in categories: [RecordCategory]) -> RecordCategory? {
        guard let name = string(value) else { return nil }
        return categories.first { $0.displayName.compare(name, options: [.caseInsensitive]) == .orderedSame }
    }

    /// Minutes are kept only when they are a believable length for one plan.
    static func minutes(_ value: Any?) -> Int? {
        guard let number = integer(value), (1...1_440).contains(number) else { return nil }
        return number
    }

    /// A day from the three date forms the instructions allow. The arithmetic
    /// is done here, not by the model.
    static func day(in object: [String: Any], now: Date, calendar: Calendar = .current) -> Date? {
        let today = calendar.startOfDay(for: now)
        if let text = string(object["date"]) {
            let parts = text.split(separator: "-").compactMap { Int($0) }
            if parts.count == 3, text.count == 10,
               let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
               calendar.component(.month, from: date) == parts[1], calendar.component(.day, from: date) == parts[2] {
                return calendar.startOfDay(for: date)
            }
        }
        if let offset = integer(object["offset_days"]), (0...730).contains(offset) {
            return calendar.date(byAdding: .day, value: offset, to: today)
        }
        if let weekday = integer(object["weekday"]), (1...7).contains(weekday) {
            let weeks = min(8, max(0, integer(object["week_offset"]) ?? 0))
            let monday = PlanWorkflow.weekStart(today, calendar: calendar)
            guard var target = calendar.date(byAdding: .day, value: weeks * 7 + weekday - 1, to: monday) else { return nil }
            // “周三” said on a Thursday means the coming Wednesday.
            if weeks == 0, target < today { target = calendar.date(byAdding: .day, value: 7, to: target) ?? target }
            return target
        }
        return nil
    }

    private static func schedule(in object: [String: Any]) -> (kind: ScheduleKind, hour: Int, minute: Int)? {
        switch string(object["part"]) {
        case "morning": return (.morning, 0, 0)
        case "afternoon": return (.afternoon, 0, 0)
        case "evening": return (.evening, 0, 0)
        case "exact":
            guard let time = string(object["time"]) else { return nil }
            let parts = time.split(separator: ":").compactMap { Int($0) }
            guard parts.count == 2, (0...23).contains(parts[0]), (0...59).contains(parts[1]) else { return nil }
            return (.exactTime, parts[0], parts[1])
        default: return nil
        }
    }

    private static func title(_ value: Any?, limit: Int = 80) -> String? {
        guard let text = string(value) else { return nil }
        let line = text.components(separatedBy: .newlines).joined(separator: " ")
        return String(line.prefix(limit))
    }

    static func entrySuggestion(from answer: String, original: String, now: Date,
                                categories: [RecordCategory], calendar: Calendar = .current) throws -> EntrySuggestion {
        let object = try object(in: answer)
        var result = EntrySuggestion()
        let clean = original.trimmingCharacters(in: .whitespacesAndNewlines)
        if let text = title(object["title"]), text != clean { result.title = text }
        result.day = day(in: object, now: now, calendar: calendar)
        if let schedule = schedule(in: object) {
            result.scheduleKind = schedule.kind; result.hour = schedule.hour; result.minute = schedule.minute
        }
        result.minutes = minutes(object["minutes"])
        result.category = category(object["category"], in: categories)
        switch string(object["repeat"]) {
        case "daily": result.repeatRule = .daily
        case "weekdays": result.repeatRule = .weekdays
        case "weekly": result.repeatRule = .weekly
        case "monthly": result.repeatRule = .monthly
        default: break
        }
        let items = (object["items"] as? [Any] ?? []).compactMap { title($0) }
        var seen = Set<String>()
        let parts = items.filter { seen.insert($0).inserted }
        if (2...8).contains(parts.count) { result.parts = parts }
        // Today is where a quick plan already lands.
        if let day = result.day, calendar.isDate(day, inSameDayAs: now) { result.day = nil }
        // A title on its own is not worth a strip under the row.
        if !result.hasDetails { result.title = nil }
        return result
    }

    static func steps(from answer: String, plan: CheckInItem, now: Date = Date(),
                      calendar: Calendar = .current) throws -> [PlanDraft] {
        let object = try object(in: answer)
        // Steps of a carried-over plan start today, not on the day it was first meant for.
        let day = max(calendar.startOfDay(for: plan.scheduledStart), calendar.startOfDay(for: now))
        let drafts = (object["steps"] as? [Any] ?? []).compactMap { raw -> PlanDraft? in
            let item = raw as? [String: Any]
            guard let text = title(item?["title"] ?? raw, limit: 60) else { return nil }
            return PlanDraft(title: text, day: day, minutes: minutes(item?["minutes"]), category: plan.category)
        }
        guard !drafts.isEmpty else { throw AIRequestError.unreadableAnswer }
        return Array(drafts.prefix(8))
    }

    static func checklist(from answer: String) throws -> [String] {
        let object = try object(in: answer)
        var seen = Set<String>()
        let items = (object["items"] as? [Any] ?? []).compactMap { title($0, limit: 120) }.filter { seen.insert($0).inserted }
        guard !items.isEmpty else { throw AIRequestError.unreadableAnswer }
        return Array(items.prefix(30))
    }

    static func conversionHints(from answer: String, rowCount: Int, now: Date,
                                categories: [RecordCategory], calendar: Calendar = .current) throws -> [Int: ConversionHint] {
        let object = try object(in: answer)
        let today = calendar.startOfDay(for: now)
        var hints: [Int: ConversionHint] = [:]
        for raw in object["rows"] as? [Any] ?? [] {
            guard let row = raw as? [String: Any], let index = integer(row["index"]), (0..<rowCount).contains(index) else { continue }
            // A row is never turned into a plan for a day already gone.
            let hint = ConversionHint(day: day(in: row, now: now, calendar: calendar).map { max(today, $0) },
                                      category: category(row["category"], in: categories))
            if hint.day != nil || hint.category != nil { hints[index] = hint }
        }
        return hints
    }

    /// `record` is the text the model was given. With it, a sentence naming a
    /// number the record does not have is taken out; nothing left of the note
    /// means the draft does not hold.
    static func reflection(from answer: String, plans: [CheckInItem], record: String? = nil) throws -> ReflectionDraft {
        let object = try object(in: answer)
        guard var note = string(object["note"]) else { throw AIRequestError.unreadableAnswer }
        var nextFocus = string(object["next_focus"]) ?? ""
        if let record {
            let allowed = allowedNumbers(record: record)
            note = keepingRecordedNumbers(note, allowed: allowed)
            nextFocus = keepingRecordedNumbers(nextFocus, allowed: allowed)
            guard !note.isEmpty else { throw AIDraftCheckError.reflectionMismatch }
        }
        let evidence = (object["evidence"] as? [Any] ?? []).compactMap { raw -> ReflectionDraft.Evidence? in
            guard let item = raw as? [String: Any], let point = string(item["point"]) else { return nil }
            let cited = (item["plans"] as? [Any] ?? []).compactMap { integer($0) }
                .filter { $0 >= 1 && $0 <= plans.count }.map { plans[$0 - 1] }
            // A point that rests on nothing in the record is not shown.
            guard !cited.isEmpty else { return nil }
            let kept = Array(cited.prefix(4))
            return ReflectionDraft.Evidence(point: String(point.prefix(80)), planTitles: kept.map(\.title),
                                            planIDs: kept.map(\.id))
        }
        return ReflectionDraft(note: String(note.prefix(600)),
                               nextFocus: String(nextFocus.prefix(120)),
                               evidence: Array(evidence.prefix(3)))
    }

    // MARK: Numbers in a drafted reflection

    /// Ends a sentence; the mark stays with it.
    static let sentenceEnds: Set<Character> = ["。", "！", "？", "；", "!", "?", ";", "\n"]

    static func sentences(_ text: String) -> [String] {
        var result: [String] = []
        var current = ""
        for character in text {
            current.append(character)
            if sentenceEnds.contains(character) {
                result.append(current)
                current = ""
            }
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    private static let digitNumber = try! NSRegularExpression(pattern: #"[0-9]+(?:\.[0-9]+)?"#)
    private static let chineseCount = try! NSRegularExpression(
        pattern: "([零一二两三四五六七八九十百]+)(?=件|项|条|次|天|个|周|小时|分钟)")

    /// Numbers written in a sentence: runs of digits (with a decimal point),
    /// and Chinese numerals right before 件 项 条 次 天 个 周 小时 分钟 (not a bare 分:
    /// 十分顺利 means "very smooth").
    static func numbers(in text: String) -> [Double] {
        let ns = text as NSString
        let whole = NSRange(location: 0, length: ns.length)
        var found = digitNumber.matches(in: text, range: whole).compactMap { Double(ns.substring(with: $0.range)) }
        for match in chineseCount.matches(in: text, range: whole) {
            if let value = QuickEntryParser.number(ns.substring(with: match.range(at: 1))) { found.append(Double(value)) }
        }
        return found
    }

    /// Every number the record sent to the model has (titles included), and 1 to 7.
    static func allowedNumbers(record: String) -> Set<Double> {
        // The [3] in front of each plan only numbers it for citing; it is not a fact of the week.
        let facts = record.replacingOccurrences(of: #"\[[0-9]+\]"#, with: " ", options: .regularExpression)
        return Set(numbers(in: facts)).union((1...7).map(Double.init))
    }

    /// The text without the sentences that name a number not in `allowed`.
    static func keepingRecordedNumbers(_ text: String, allowed: Set<Double>) -> String {
        sentences(text).filter { numbers(in: $0).allSatisfy(allowed.contains) }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func planDrafts(in object: [String: Any], limit: Int, fallbackCategory: RecordCategory,
                                   categories: [RecordCategory],
                                   day: ([String: Any]) -> Date?) throws -> [PlanDraft] {
        let drafts = (object["plans"] as? [Any] ?? []).compactMap { raw -> PlanDraft? in
            guard let item = raw as? [String: Any], let text = title(item["title"], limit: 60), let date = day(item) else { return nil }
            var draft = PlanDraft(title: text, day: date, minutes: minutes(item["minutes"]),
                                  category: category(item["category"], in: categories) ?? fallbackCategory,
                                  reason: String((string(item["reason"]) ?? "").prefix(40)))
            if let schedule = schedule(in: item) {
                draft.scheduleKind = schedule.kind; draft.hour = schedule.hour; draft.minute = schedule.minute
            }
            return draft
        }
        guard !drafts.isEmpty else { throw AIRequestError.unreadableAnswer }
        return Array(drafts.prefix(limit))
    }

    /// The week after `week`. A day of it already gone moves to today.
    static func nextWeekDrafts(from answer: String, week: Date, now: Date, fallbackCategory: RecordCategory,
                               categories: [RecordCategory], calendar: Calendar = .current) throws -> [PlanDraft] {
        let monday = PlanWorkflow.weekStart(week, calendar: calendar)
        let today = calendar.startOfDay(for: now)
        return try planDrafts(in: object(in: answer), limit: 8, fallbackCategory: fallbackCategory, categories: categories) { item in
            guard let weekday = integer(item["weekday"]), (1...7).contains(weekday),
                  let day = calendar.date(byAdding: .day, value: 7 + weekday - 1, to: monday) else { return nil }
            return max(today, day)
        }
    }

    /// 下周 is the week after the one reviewed, offered only while it has not
    /// passed: the reviewed week is this week or last week.
    static func canDraftNextWeek(week: Date, now: Date, calendar: Calendar = .current) -> Bool {
        let reviewed = PlanWorkflow.weekStart(week, calendar: calendar)
        let current = PlanWorkflow.weekStart(now, calendar: calendar)
        guard let previous = calendar.date(byAdding: .day, value: -7, to: current) else { return reviewed == current }
        return reviewed == current || reviewed == previous
    }

    /// The offer under 周复盘 after it was saved: once per week, while the
    /// week can still be drafted for and the entry points are shown.
    static func showsNextWeekOffer(reflection: WeeklyReflection?, week: Date, now: Date, showsEntries: Bool,
                                   handled: Bool, calendar: Calendar = .current) -> Bool {
        guard showsEntries, !handled, let reflection,
              !(reflection.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && reflection.nextFocus.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) else { return false }
        return canDraftNextWeek(week: week, now: now, calendar: calendar)
    }

    static func preparationDrafts(from answer: String, target: Date, now: Date, fallbackCategory: RecordCategory,
                                  categories: [RecordCategory], calendar: Calendar = .current) throws -> [PlanDraft] {
        let today = calendar.startOfDay(for: now)
        let targetDay = calendar.startOfDay(for: target)
        let drafts = try planDrafts(in: object(in: answer), limit: 8, fallbackCategory: fallbackCategory, categories: categories) { item in
            guard let before = integer(item["days_before"]), before >= 0,
                  let date = calendar.date(byAdding: .day, value: -before, to: targetDay) else { return nil }
            // A step can never land in the past or after the day itself.
            return min(targetDay, max(today, date))
        }
        return drafts.sorted { $0.day < $1.day }
    }

    static func importDrafts(from answer: String, now: Date, fallbackCategory: RecordCategory,
                             categories: [RecordCategory], calendar: Calendar = .current) throws -> [PlanDraft] {
        let today = calendar.startOfDay(for: now)
        return try planDrafts(in: object(in: answer), limit: 12, fallbackCategory: fallbackCategory, categories: categories) { item in
            max(today, day(in: item, now: now, calendar: calendar) ?? today)
        }
    }

    static func libraryFilter(from answer: String, now: Date, categories: [RecordCategory],
                              calendar: Calendar = .current) throws -> LibraryFilter {
        let object = try object(in: answer)
        var filter = LibraryFilter()
        // “全部”-like answers say “no limit”; they are read, not refused.
        var understood = false
        let keyword = string(object["keyword"]) ?? ""
        if unlimitedWords.contains(keyword.lowercased()) {
            understood = true
        } else {
            filter.keyword = String(keyword.prefix(30))
        }
        if let category = category(object["category"], in: categories) {
            filter.category = category.rawValue
        } else if let word = string(object["category"]), unlimitedWords.contains(word.lowercased()) {
            understood = true
        }
        if let status = string(object["status"]) {
            if ["planned", "inProgress", "completed", "skipped", "archived"].contains(status) { filter.status = status }
            else if unlimitedWords.contains(status.lowercased()) { understood = true }
        }
        let today = calendar.startOfDay(for: now)
        let monday = PlanWorkflow.weekStart(today, calendar: calendar)
        func week(_ offset: Int) {
            guard let start = calendar.date(byAdding: .day, value: offset * 7, to: monday),
                  let end = calendar.date(byAdding: .day, value: 6, to: start) else { return }
            filter.period = "range"; filter.from = start; filter.to = end
        }
        func month(_ offset: Int) {
            guard let first = calendar.date(from: calendar.dateComponents([.year, .month], from: today)),
                  let start = calendar.date(byAdding: .month, value: offset, to: first),
                  let next = calendar.date(byAdding: .month, value: 1, to: start),
                  let end = calendar.date(byAdding: .day, value: -1, to: next) else { return }
            filter.period = "range"; filter.from = start; filter.to = end
        }
        if let period = string(object["period"]), unlimitedWords.contains(period.lowercased()) { understood = true }
        switch string(object["period"]) {
        case "past": filter.period = "past"
        case "today": filter.period = "today"
        case "future": filter.period = "future"
        case "this_week": week(0)
        case "last_week": week(-1)
        case "next_week": week(1)
        case "this_month": month(0)
        case "last_month": month(-1)
        case "range":
            if let from = day(in: ["date": object["from"] ?? NSNull()], now: now, calendar: calendar),
               let to = day(in: ["date": object["to"] ?? NSNull()], now: now, calendar: calendar), from <= to {
                filter.period = "range"; filter.from = from; filter.to = to
            }
        default: break
        }
        guard filter != LibraryFilter() || understood else { throw AIRequestError.unreadableAnswer }
        return filter
    }

    /// Answers meaning “any”: 全部类型, all, 不限…
    static let unlimitedWords: Set<String> = ["all", "any", "全部", "所有", "全部类型", "所有类型", "全部状态", "所有状态",
                                              "全部日期", "所有日期", "全部计划", "所有计划", "不限"]

    // MARK: What is sent

    static func breakdownInput(_ plan: CheckInItem) -> String {
        var lines = ["计划：\(plan.title)"]
        if !plan.note.isEmpty { lines.append("备注：\(plan.note)") }
        if let minutes = plan.plannedDurationMinutes { lines.append("预计投入：\(minutes) 分钟") }
        return lines.joined(separator: "\n")
    }

    static func conversionInput(memoTitle: String, rows: [String]) -> String {
        var lines: [String] = []
        if !memoTitle.isEmpty { lines.append("备忘标题：\(memoTitle)") }
        for (index, row) in rows.enumerated() { lines.append("[\(index)] \(row.prefix(200))") }
        return lines.joined(separator: "\n")
    }

    /// One week as plain facts, with the plans numbered so that a point in the
    /// draft can cite them. Every number in it is computed here.
    static func weekStats(snapshot: PlanSnapshot, week: Date, calendar: Calendar = .current) -> WeekRecordStats {
        let summary = WorkflowWeekSummary.make(snapshot: snapshot, week: week, calendar: calendar)
        let skipped = summary.scheduled.filter { $0.status == .skipped }.count
        let open = summary.carryover.count + summary.pending.count
        return WeekRecordStats(planned: summary.completed.count + open + skipped, completed: summary.completed.count,
                               open: open, skipped: skipped, actualMinutes: summary.actualMinutes)
    }

    static func weekRecord(snapshot: PlanSnapshot, week: Date, now: Date,
                           calendar: Calendar = .current) -> (text: String, plans: [CheckInItem]) {
        let summary = WorkflowWeekSummary.make(snapshot: snapshot, week: week, calendar: calendar)
        let start = PlanWorkflow.weekStart(week, calendar: calendar)
        let end = calendar.date(byAdding: .day, value: 6, to: start) ?? start
        let skipped = summary.scheduled.filter { $0.status == .skipped }
        let open = (summary.carryover + summary.pending).sorted { $0.scheduledStart < $1.scheduledStart }
        var plans: [CheckInItem] = []
        func number(_ plan: CheckInItem) -> Int { plans.append(plan); return plans.count }
        func actual(_ plan: CheckInItem) -> Int {
            let seconds = snapshot.records.filter { $0.checkInItemID == plan.id && $0.hasPreciseTime }
                .reduce(0.0) { $0 + max(0, $1.endDate.timeIntervalSince($1.startDate)) }
            return seconds > 0 ? Int(ceil(seconds / 60)) : 0
        }
        func dayLabel(_ date: Date) -> String {
            let c = calendar.dateComponents([.month, .day], from: date)
            return "\(c.month ?? 1)月\(c.day ?? 1)日"
        }
        var lines = ["这一周：\(dayLabel(start))–\(dayLabel(end))",
                     "完成 \(summary.completed.count) 项，没做完 \(open.count) 项，跳过 \(skipped.count) 项；"
                        + "预计投入 \(summary.estimatedMinutes) 分钟，实际专注 \(summary.actualMinutes) 分钟。"]
        let key = PlanWorkflow.weekKey(week, calendar: calendar)
        for goal in snapshot.weeklyGoals where goal.weekStart == key {
            let progress = WorkflowWeekSummary.make(snapshot: snapshot, week: week, category: goal.category, calendar: calendar)
            var parts: [String] = []
            if let count = goal.targetCount { parts.append("完成 \(count) 项（已 \(progress.completed.count) 项）") }
            if let minutes = goal.targetMinutes { parts.append("专注 \(minutes) 分钟（已 \(progress.actualMinutes) 分钟）") }
            lines.append("周目标（\(goal.category?.displayName ?? "全部类型")）：" + parts.joined(separator: "，"))
        }
        if !summary.completed.isEmpty {
            lines.append("已完成：")
            for plan in summary.completed.prefix(30) {
                let minutes = actual(plan)
                lines.append("[\(number(plan))] \(plan.title)（\(plan.category.displayName)\(minutes > 0 ? "，实际 \(minutes) 分钟" : "")）")
            }
        }
        if !open.isEmpty {
            lines.append("没做完：")
            for plan in open.prefix(30) {
                let late = CarryOverSeal.daysLate(scheduledStart: plan.scheduledStart, on: now, calendar: calendar)
                lines.append("[\(number(plan))] \(plan.title)（\(plan.category.displayName)\(late.map { "，已顺延 \($0) 天" } ?? "")）")
            }
        }
        if !skipped.isEmpty {
            lines.append("已跳过：")
            for plan in skipped.prefix(15) { lines.append("[\(number(plan))] \(plan.title)（\(plan.category.displayName)）") }
        }
        return (lines.joined(separator: "\n"), plans)
    }

    static func nextWeekInput(snapshot: PlanSnapshot, week: Date, now: Date, calendar: Calendar = .current) -> String {
        var text = weekRecord(snapshot: snapshot, week: week, now: now, calendar: calendar).text
        let key = PlanWorkflow.weekKey(week, calendar: calendar)
        if let reflection = snapshot.weeklyReflections.first(where: { $0.weekStart == key }) {
            if !reflection.note.isEmpty { text += "\n本周复盘：\(reflection.note.prefix(600))" }
            if !reflection.nextFocus.isEmpty { text += "\n下周关注：\(reflection.nextFocus.prefix(200))" }
        }
        return text
    }

    static func preparationInput(title: String, target: Date, daysLeft: Int, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: target)
        return String(format: "日子：%@\n日期：%04d-%02d-%02d（还有 %d 天）", title, c.year ?? 2000, c.month ?? 1, c.day ?? 1, daysLeft)
    }
}
