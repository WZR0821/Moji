import Foundation

/// One thing recognised on the device in a sentence typed into the home
/// page's field: a day, a time, a length, a repeat or a type. It becomes a
/// chip under the field and changes nothing until the user taps it.
struct QuickEntryToken: Equatable, Identifiable {
    enum Kind: Int, CaseIterable {
        case day, time, duration, repeatRule, category
    }

    var kind: Kind
    /// What the chip says: 周三, 15:00, 45 分钟, 每周, 工作.
    var text: String
    /// Where the words sit in the sentence, in UTF-16 offsets. A type keeps
    /// its words in the title, so it has none.
    var range: NSRange?
    var day: Date?
    var scheduleKind: ScheduleKind?
    var hour = 0
    var minute = 0
    var minutes: Int?
    var repeatRule: PlanRepeatRule?
    var category: RecordCategory?

    /// Same kind, same words: a chip stays adopted while typing goes on, and
    /// becomes a new chip once its words change.
    var id: String { "\(kind.rawValue)|\(text)" }
}

/// Reads days, times, lengths, repeats and types out of one typed plan with
/// fixed rules, so the chips appear while typing, without a network and
/// without sending anything. Pure: the same sentences replay in tests.
enum QuickEntryParser {
    // MARK: Public

    static func tokens(in text: String, now: Date = Date(), calendar: Calendar = .current,
                       categories: [RecordCategory], defaultCategory: RecordCategory?) -> [QuickEntryToken] {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var scan = Scan(text: text, today: calendar.startOfDay(for: now), calendar: calendar)
        scan.readRepeat()
        scan.readDay()
        scan.readTime()
        scan.readDuration()
        scan.readCategory(categories.filter { $0 != defaultCategory })
        return scan.tokens.sorted { $0.kind.rawValue < $1.kind.rawValue }
    }

    /// The sentence without the words of the adopted chips. Words shared by
    /// several chips (明早 is a day and a time) go only when all of them are
    /// adopted. Empty after stripping means the sentence is kept as it was.
    static func title(from text: String, tokens: [QuickEntryToken], adopted: Set<String>) -> String {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = text as NSString
        var kept: [unichar] = []
        var removed = false
        for index in 0..<ns.length {
            let covering = tokens.filter { token in
                guard let range = token.range else { return false }
                return NSLocationInRange(index, range)
            }
            if !covering.isEmpty, covering.allSatisfy({ adopted.contains($0.id) }) {
                removed = true
            } else {
                kept.append(ns.character(at: index))
            }
        }
        guard removed else { return clean }
        var result = String(utf16CodeUnits: kept, count: kept.count)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        let edges = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "，,、。.；;：:"))
        while true {
            let before = result
            result = result.trimmingCharacters(in: edges)
            if result.hasPrefix("的") { result.removeFirst() }
            if result == before { break }
        }
        return result.isEmpty ? clean : result
    }

    /// The adopted chips as one suggestion for `EntrySuggestion.applied(to:)`;
    /// nil when nothing is adopted.
    static func suggestion(text: String, tokens: [QuickEntryToken], adopted: Set<String>) -> EntrySuggestion? {
        let chosen = tokens.filter { adopted.contains($0.id) }
        guard !chosen.isEmpty else { return nil }
        var result = EntrySuggestion()
        for token in chosen {
            switch token.kind {
            case .day: result.day = token.day
            case .time:
                result.scheduleKind = token.scheduleKind
                result.hour = token.hour
                result.minute = token.minute
            case .duration: result.minutes = token.minutes
            case .repeatRule: result.repeatRule = token.repeatRule
            case .category: result.category = token.category
            }
        }
        let title = title(from: text, tokens: tokens, adopted: adopted)
        if title != text.trimmingCharacters(in: .whitespacesAndNewlines) { result.title = title }
        return result
    }

    // MARK: Numbers

    /// 0–999 written in digits or in Chinese numerals (十五, 二十, 两, 一百二十).
    static func number(_ text: String) -> Int? {
        if let value = Int(text) { return value }
        let digits: [Character: Int] = ["零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
                                        "五": 5, "六": 6, "七": 7, "八": 8, "九": 9]
        guard !text.isEmpty else { return nil }
        var total = 0
        var digit: Int?
        for character in text {
            if let value = digits[character] {
                // 二三 is not a number; 一百零五 is.
                if let digit, digit != 0 { return nil }
                digit = value
            } else if character == "百" {
                total += (digit ?? 1) * 100
                digit = nil
            } else if character == "十" {
                total += (digit ?? 1) * 10
                digit = nil
            } else {
                return nil
            }
        }
        return total + (digit ?? 0)
    }

    /// 1–99 in Chinese numerals: 三, 十, 十二, 二十五.
    static func chineseNumeral(_ value: Int) -> String {
        let names = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"]
        guard (1...99).contains(value) else { return "\(value)" }
        if value < 10 { return names[value] }
        let tens = value / 10, ones = value % 10
        return (tens == 1 ? "" : names[tens]) + "十" + (ones == 0 ? "" : names[ones])
    }

    // MARK: Patterns

    fileprivate static let qualifiers = "早上|早晨|清晨|上午|中午|下午|午后|傍晚|晚上|晚间|夜里"
    fileprivate static let clockCore = #"(?:(?<![0-9])(?<ch>[0-9]{1,2})[:：](?<cm>[0-9]{2})(?![0-9])|(?:(?<![0-9.])(?<dh>[0-9]{1,2})|(?<![零〇一二两三四五六七八九十百])(?<zh>[零〇一二两三四五六七八九十]{1,3}))点(?<zhong>钟)?(?<mm>半|一刻|三刻|(?:(?<dm>[0-9]{1,2})|(?<zm>[零〇一二两三四五六七八九十]{1,3}))分(?!钟))?)(?<suffix>之前|以前|前|左右)?"#

    fileprivate static let repeatRegex = regex(
        #"(?<workday>(?:每个工作日|工作日)(?![志记报程]))|(?<daily>每天|每日|(?<![今明后昨前每])天天)|(?<weekly>每个星期|每星期|每礼拜|每周)(?<wwd>[一二三四五六日天1-7](?![次回遍趟]))?|(?<monthly>每个月|每月)"#)
    fileprivate static let dayRegex = regex(
        #"(?<today>今天|今日)|(?<compound>今早|今晨|今晚|明早|明晨|明晚)|(?<rel>大后天|后天|明天|明日)|(?<wp>下下|下个|下|这个|这|本)?(?:(?:周|星期|礼拜)(?<wd>[一二三四五六日天1-7])(?![次回遍趟])|(?<weekend>周末))|(?<![0-9])(?<mo>[0-9]{1,2})月(?<md>[0-9]{1,2})(?:日|号)?(?![0-9])|(?<![0-9月])(?<dd>[0-9]{1,2})(?:日|号)"#)
    fileprivate static let timeRegex = regex("(?:(?<q>" + qualifiers + #")\s?)?"# + clockCore)
    fileprivate static let followingClockRegex = regex(#"\s?"# + clockCore)
    fileprivate static let periodRegex = regex("(?<q>" + qualifiers + ")")
    fileprivate static let durationRegex = regex(
        #"(?:(?<![0-9.])(?<hhd>[0-9]+(?:\.[0-9]+)?)|(?<![零〇一二两三四五六七八九十百])(?<hhz>[零〇一二两三四五六七八九十百]+))\s?个?\s?半(?:小时|钟头)|(?:(?<![0-9.])(?<hd>[0-9]+(?:\.[0-9]+)?)|(?<![零〇一二两三四五六七八九十百])(?<hz>[零〇一二两三四五六七八九十百]+))\s?个?\s?(?:小时|钟头)(?:\s?(?:(?<hmd>[0-9]+)|(?<hmz>[零〇一二两三四五六七八九十]+))\s?分钟)?|(?<half>半个?(?:小时|钟头))|(?<quarter>一刻钟)|(?:(?<![0-9.])(?<md>[0-9]+)|(?<![零〇一二两三四五六七八九十百])(?<mz>[零〇一二两三四五六七八九十百]+))\s?(?:分钟|(?i:mins|min)(?![a-zA-Z]))"#)

    /// After these, “N点” is a count of points, not a time: 提三点意见.
    fileprivate static let pointNouns = ["意见", "建议", "想法", "内容", "要求", "原因", "理由", "问题", "看法", "心得", "体会", "收获"]
    /// Before these, “一点” means “a little”: 快一点, 早一点.
    fileprivate static let littleBefore: Set<Character> = ["快", "慢", "多", "少", "早", "晚", "好", "大", "小", "高", "低", "再"]

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // The patterns are fixed text; a typo shows up in the first test run.
        try! NSRegularExpression(pattern: pattern)
    }

    fileprivate enum Part {
        case morning, noon, afternoon, evening

        init?(_ word: String?) {
            switch word {
            case "早上", "早晨", "清晨", "上午", "今早", "今晨", "明早", "明晨": self = .morning
            case "中午": self = .noon
            case "下午", "午后": self = .afternoon
            case "傍晚", "晚上", "晚间", "夜里", "今晚", "明晚": self = .evening
            default: return nil
            }
        }
    }
}

private struct Scan {
    let ns: NSString
    let text: String
    let today: Date
    let calendar: Calendar
    var tokens: [QuickEntryToken] = []
    var claimed: [NSRange] = []
    var found: Set<QuickEntryToken.Kind> = []

    init(text: String, today: Date, calendar: Calendar) {
        self.text = text
        self.ns = text as NSString
        self.today = today
        self.calendar = calendar
    }

    private var whole: NSRange { NSRange(location: 0, length: ns.length) }

    private func isFree(_ range: NSRange) -> Bool {
        !claimed.contains { NSIntersectionRange($0, range).length > 0 }
    }

    private func group(_ match: NSTextCheckingResult, _ name: String) -> String? {
        let range = match.range(withName: name)
        guard range.location != NSNotFound else { return nil }
        return ns.substring(with: range)
    }

    private mutating func claim(_ range: NSRange) { claimed.append(range) }

    // MARK: Repeat

    mutating func readRepeat() {
        for match in QuickEntryParser.repeatRegex.matches(in: text, range: whole) where isFree(match.range) {
            let rule: PlanRepeatRule
            if group(match, "workday") != nil { rule = .weekdays }
            else if group(match, "daily") != nil { rule = .daily }
            else if group(match, "weekly") != nil { rule = .weekly }
            else { rule = .monthly }
            claim(match.range)
            found.insert(.repeatRule)
            tokens.append(QuickEntryToken(kind: .repeatRule, text: rule.displayName, range: match.range, repeatRule: rule))
            // 每周三 also says which day the first one falls on.
            if let mark = group(match, "wwd"), let weekday = Self.weekday(mark) {
                found.insert(.day)
                if let day = weekdayDate(weeks: 0, weekday: weekday), day != today {
                    tokens.append(QuickEntryToken(kind: .day, text: "周" + Self.weekdayName(weekday),
                                                  range: match.range, day: day))
                }
            }
            return
        }
    }

    // MARK: Day

    mutating func readDay() {
        guard !found.contains(.day) else { return }
        for match in QuickEntryParser.dayRegex.matches(in: text, range: whole) where isFree(match.range) {
            if group(match, "today") != nil {
                // Today is where a quick plan lands anyway: no chip, but the
                // words are taken so nothing else reads them.
                claim(match.range)
                found.insert(.day)
                return
            }
            if let word = group(match, "compound") {
                readCompound(word, range: match.range)
                return
            }
            var day: Date?
            var label = ""
            if let word = group(match, "rel") {
                let offset = word == "大后天" ? 3 : word == "后天" ? 2 : 1
                day = calendar.date(byAdding: .day, value: offset, to: today)
                label = offset == 3 ? "大后天" : offset == 2 ? "后天" : "明天"
            } else if group(match, "wd") != nil || group(match, "weekend") != nil {
                let prefix = group(match, "wp") ?? ""
                let weeks = prefix == "下下" ? 2 : (prefix == "下" || prefix == "下个") ? 1 : 0
                let weekday = group(match, "wd").flatMap(Self.weekday) ?? 6
                day = weekdayDate(weeks: weeks, weekday: weekday)
                label = (weeks == 2 ? "下下" : weeks == 1 ? "下" : "") + "周" + Self.weekdayName(weekday)
            } else if let month = group(match, "mo").flatMap({ Int($0) }),
                      let date = group(match, "md").flatMap({ Int($0) }) {
                day = monthDay(month: month, day: date)
            } else if let date = group(match, "dd").flatMap({ Int($0) }) {
                day = dayOfMonth(date)
            }
            // A date that does not exist (2月30日) is left as words.
            guard let day else { continue }
            if label.isEmpty {
                let parts = calendar.dateComponents([.month, .day], from: day)
                label = "\(parts.month ?? 1)月\(parts.day ?? 1)日"
            }
            claim(match.range)
            found.insert(.day)
            if day != today {
                tokens.append(QuickEntryToken(kind: .day, text: label, range: match.range, day: day))
            }
            return
        }
    }

    /// 今早, 今晚, 明早, 明晚: a day and a part of it in one word, and with a
    /// time right after (今晚8点) the time takes the word with it.
    private mutating func readCompound(_ word: String, range: NSRange) {
        let part = QuickEntryParser.Part(word) ?? .morning
        let isTomorrow = word.hasPrefix("明")
        found.insert(.day)
        found.insert(.time)
        if isTomorrow, let day = calendar.date(byAdding: .day, value: 1, to: today) {
            tokens.append(QuickEntryToken(kind: .day, text: "明天", range: range, day: day))
        }
        let after = NSRange(location: NSMaxRange(range), length: ns.length - NSMaxRange(range))
        if let clock = QuickEntryParser.followingClockRegex.firstMatch(
                in: text, options: [.anchored, .withTransparentBounds], range: after),
           isFree(clock.range), let time = resolveClock(clock, part: part) {
            let joined = NSUnionRange(range, clock.range)
            claim(joined)
            tokens.append(QuickEntryToken(kind: .time, text: String(format: "%02d:%02d", time.hour, time.minute),
                                          range: joined, scheduleKind: .exactTime, hour: time.hour, minute: time.minute))
        } else {
            claim(range)
            let kind: ScheduleKind = part == .evening ? .evening : .morning
            tokens.append(QuickEntryToken(kind: .time, text: kind.displayName, range: range, scheduleKind: kind))
        }
    }

    /// 1 = Monday … 7 = Sunday.
    private static func weekday(_ mark: String) -> Int? {
        switch mark {
        case "一", "1": return 1
        case "二", "2": return 2
        case "三", "3": return 3
        case "四", "4": return 4
        case "五", "5": return 5
        case "六", "6": return 6
        case "日", "天", "7": return 7
        default: return nil
        }
    }

    private static func weekdayName(_ weekday: Int) -> String {
        ["一", "二", "三", "四", "五", "六", "日"][max(1, min(7, weekday)) - 1]
    }

    /// The same arithmetic as `AIDrafting.day`: a day of this week that has
    /// passed means the one next week.
    private func weekdayDate(weeks: Int, weekday: Int) -> Date? {
        let monday = PlanWorkflow.weekStart(today, calendar: calendar)
        guard var target = calendar.date(byAdding: .day, value: weeks * 7 + weekday - 1, to: monday) else { return nil }
        if weeks == 0, target < today { target = calendar.date(byAdding: .day, value: 7, to: target) ?? target }
        return target
    }

    private func exactDay(year: Int, month: Int, day: Int) -> Date? {
        guard let date = calendar.date(from: DateComponents(year: year, month: month, day: day)),
              calendar.component(.month, from: date) == month,
              calendar.component(.day, from: date) == day else { return nil }
        return calendar.startOfDay(for: date)
    }

    /// 12月5日: this year's, or next year's once it has passed.
    private func monthDay(month: Int, day: Int) -> Date? {
        let year = calendar.component(.year, from: today)
        guard let date = exactDay(year: year, month: month, day: day) else { return nil }
        return date < today ? exactDay(year: year + 1, month: month, day: day) : date
    }

    /// 3号: this month's, or next month's once it has passed.
    private func dayOfMonth(_ day: Int) -> Date? {
        let parts = calendar.dateComponents([.year, .month], from: today)
        guard let year = parts.year, let month = parts.month,
              let date = exactDay(year: year, month: month, day: day) else { return nil }
        guard date < today else { return date }
        return month == 12 ? exactDay(year: year + 1, month: 1, day: day) : exactDay(year: year, month: month + 1, day: day)
    }

    // MARK: Time

    mutating func readTime() {
        guard !found.contains(.time) else { return }
        var best: (range: NSRange, token: QuickEntryToken)?
        func consider(_ range: NSRange, _ token: QuickEntryToken) {
            if let current = best {
                if range.location > current.range.location { return }
                if range.location == current.range.location, range.length <= current.range.length { return }
            }
            best = (range, token)
        }
        for match in QuickEntryParser.timeRegex.matches(in: text, range: whole) where isFree(match.range) {
            let word = group(match, "q")
            if let time = resolveClock(match, part: QuickEntryParser.Part(word), hasPart: word != nil) {
                consider(match.range, QuickEntryToken(kind: .time, text: String(format: "%02d:%02d", time.hour, time.minute),
                                                      range: match.range, scheduleKind: .exactTime,
                                                      hour: time.hour, minute: time.minute))
                break
            }
            // 晚上12点 is not read as a time; 晚上 on its own still is.
            let partRange = match.range(withName: "q")
            if partRange.location != NSNotFound, let word, let token = periodToken(word, range: partRange) {
                consider(partRange, token)
                break
            }
        }
        if let match = QuickEntryParser.periodRegex.matches(in: text, range: whole).first(where: { isFree($0.range) }),
           let token = periodToken(ns.substring(with: match.range), range: match.range) {
            consider(match.range, token)
        }
        guard let best else { return }
        claim(best.range)
        found.insert(.time)
        tokens.append(best.token)
    }

    private func periodToken(_ word: String, range: NSRange) -> QuickEntryToken? {
        switch QuickEntryParser.Part(word) {
        case .morning: return QuickEntryToken(kind: .time, text: "上午", range: range, scheduleKind: .morning)
        case .noon: return QuickEntryToken(kind: .time, text: "12:00", range: range, scheduleKind: .exactTime, hour: 12)
        case .afternoon: return QuickEntryToken(kind: .time, text: "下午", range: range, scheduleKind: .afternoon)
        case .evening: return QuickEntryToken(kind: .time, text: "晚上", range: range, scheduleKind: .evening)
        case nil: return nil
        }
    }

    /// The hour and minute of a clock match, after the part of day before it;
    /// nil when the words are not a time (提三点意见, 多读一点书).
    private func resolveClock(_ match: NSTextCheckingResult, part: QuickEntryParser.Part?,
                              hasPart: Bool = true) -> (hour: Int, minute: Int)? {
        var hour: Int
        var minute = 0
        if let text = group(match, "ch"), let value = Int(text), let minuteText = group(match, "cm"),
           let minutes = Int(minuteText) {
            hour = value
            minute = minutes
        } else {
            let chinese = group(match, "zh")
            guard let hourText = group(match, "dh") ?? chinese, let value = QuickEntryParser.number(hourText) else { return nil }
            hour = value
            switch group(match, "mm") {
            case "半": minute = 30
            case "一刻": minute = 15
            case "三刻": minute = 45
            case .some:
                guard let text = group(match, "dm") ?? group(match, "zm"), let value = QuickEntryParser.number(text) else { return nil }
                minute = value
            case nil: minute = 0
            }
            let hourRange = match.range(withName: group(match, "dh") != nil ? "dh" : "zh")
            let before: Character? = hourRange.location > 0
                ? Character(ns.substring(with: NSRange(location: hourRange.location - 1, length: 1))) : nil
            let end = NSMaxRange(match.range)
            let after = ns.substring(from: end)
            let pointEnd = NSMaxRange(hourRange) + 1
            let afterPoint = pointEnd <= ns.length ? ns.substring(from: pointEnd) : ""
            if part == nil || !hasPart {
                // 第三点 is a numbered point, not three o'clock.
                if before == "第" { return nil }
                if chinese == "一" {
                    if let before, QuickEntryParser.littleBefore.contains(before) { return nil }
                    if afterPoint.hasPrefix("点") { return nil }
                    // A bare 一点 is “a little” far more often than one o'clock.
                    if group(match, "mm") == nil, group(match, "zhong") == nil, group(match, "suffix") == nil { return nil }
                }
            }
            if QuickEntryParser.pointNouns.contains(where: { after.hasPrefix($0) || afterPoint.hasPrefix($0) }) { return nil }
        }
        switch hasPart ? part : nil {
        case .morning: break
        case .noon: if (1...2).contains(hour) { hour += 12 }
        case .afternoon: if (1...11).contains(hour) { hour += 12 }
        case .evening:
            if hour == 12 { return nil }
            if (1...11).contains(hour) { hour += 12 }
        case nil: if (1...6).contains(hour) { hour += 12 }
        }
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return (hour, minute)
    }

    // MARK: Duration

    mutating func readDuration() {
        guard !found.contains(.duration) else { return }
        for match in QuickEntryParser.durationRegex.matches(in: text, range: whole) where isFree(match.range) {
            var total: Double?
            if let hours = group(match, "hhd").flatMap(Double.init) ?? group(match, "hhz").flatMap(QuickEntryParser.number).map(Double.init) {
                total = hours * 60 + 30
            } else if let hours = group(match, "hd").flatMap(Double.init) ?? group(match, "hz").flatMap(QuickEntryParser.number).map(Double.init) {
                let extra = (group(match, "hmd") ?? group(match, "hmz")).flatMap(QuickEntryParser.number) ?? 0
                total = hours * 60 + Double(extra)
            } else if group(match, "half") != nil {
                total = 30
            } else if group(match, "quarter") != nil {
                total = 15
            } else if let value = (group(match, "md") ?? group(match, "mz")).flatMap(QuickEntryParser.number) {
                total = Double(value)
            }
            guard let total, total.isFinite else { continue }
            let minutes = Int(total.rounded())
            guard (1...1_440).contains(minutes) else { continue }
            claim(match.range)
            found.insert(.duration)
            tokens.append(QuickEntryToken(kind: .duration, text: DurationText.full(minutes: minutes),
                                          range: match.range, minutes: minutes))
            return
        }
    }

    // MARK: Type

    mutating func readCategory(_ categories: [RecordCategory]) {
        var best: (location: Int, length: Int, category: RecordCategory)?
        for category in categories {
            let name = category.displayName
            guard !name.isEmpty else { continue }
            var search = whole
            while search.length > 0 {
                let range = ns.range(of: name, options: [], range: search)
                guard range.location != NSNotFound else { break }
                if isFree(range) {
                    if best == nil || range.location < best!.location
                        || (range.location == best!.location && range.length > best!.length) {
                        best = (range.location, range.length, category)
                    }
                    break
                }
                let next = range.location + 1
                search = NSRange(location: next, length: ns.length - next)
            }
        }
        guard let best else { return }
        found.insert(.category)
        tokens.append(QuickEntryToken(kind: .category, text: best.category.displayName, range: nil, category: best.category))
    }
}
