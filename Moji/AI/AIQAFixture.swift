#if DEBUG
import Foundation

/// Debug-only stand-in for a provider, so screens that wait on a model can be
/// opened and photographed without a key or a network. `MOJI_QA_AI=ready`
/// starts with a provider set up (keys held in memory only), `empty` starts
/// as a new install does, `error` makes every request fail with 401. Nothing
/// here is compiled into a release build.
enum AIQAFixture {
    static var mode: String? { ProcessInfo.processInfo.environment["MOJI_QA_AI"] }
    static var isActive: Bool { mode != nil }

    @MainActor
    static func store() -> AISettingsStore? {
        guard let mode else { return nil }
        let suite = "moji.qa.ai"
        guard let defaults = UserDefaults(suiteName: suite) else { return nil }
        defaults.removePersistentDomain(forName: suite)
        let store = AISettingsStore(defaults: defaults, keys: AIMemoryKeyStore())
        guard mode != "empty" else { return store }
        var settings = AISettings()
        settings.isEnabled = true
        settings.activeID = AIProvider.deepSeek.rawValue
        settings.providerList = [AIProvider.deepSeek.rawValue, AIProvider.claude.rawValue]
        try? store.save(settings, keys: [AIProvider.deepSeek.rawValue: "qa-placeholder",
                                         AIProvider.claude.rawValue: "qa-placeholder"])
        return store
    }

    static var scenario: String { ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? "" }

    /// 21:30 today for the evening screens, this week's Sunday 20:30 for the
    /// letter, otherwise nil (the real clock).
    static var clock: Date? {
        let calendar = Calendar.mojiISO
        let today = Date()
        if scenario.hasPrefix("ai-wrap") || scenario == "ai-writes" {
            return calendar.date(bySettingHour: 21, minute: 30, second: 0, of: today)
        }
        if scenario.hasPrefix("ai-letter") {
            let monday = PlanWorkflow.weekStart(today, calendar: calendar)
            let sunday = calendar.date(byAdding: .day, value: 6, to: monday) ?? today
            return calendar.date(bySettingHour: 20, minute: 30, second: 0, of: sunday)
        }
        return nil
    }

    /// Whole days from today to this week's Sunday: the letter screens move
    /// the sample data that far so it falls inside the week being written about.
    static var letterShiftDays: Int {
        guard scenario.hasPrefix("ai-letter") else { return 0 }
        let calendar = Calendar.mojiISO
        let today = calendar.startOfDay(for: Date())
        let monday = PlanWorkflow.weekStart(today, calendar: calendar)
        let sunday = calendar.date(byAdding: .day, value: 6, to: monday) ?? today
        return calendar.dateComponents([.day], from: today, to: sunday).day ?? 0
    }

    static func shifted(_ snapshot: PlanSnapshot, byDays days: Int) -> PlanSnapshot {
        guard days != 0 else { return snapshot }
        let calendar = Calendar.current
        func move(_ date: Date) -> Date { calendar.date(byAdding: .day, value: days, to: date) ?? date }
        var value = snapshot
        for index in value.checkInItems.indices {
            value.checkInItems[index].scheduledStart = move(value.checkInItems[index].scheduledStart)
            value.checkInItems[index].createdAt = move(value.checkInItems[index].createdAt)
            value.checkInItems[index].completedAt = value.checkInItems[index].completedAt.map(move)
            value.checkInItems[index].actualStartDate = value.checkInItems[index].actualStartDate.map(move)
            value.checkInItems[index].actualEndDate = value.checkInItems[index].actualEndDate.map(move)
        }
        for index in value.records.indices {
            value.records[index].startDate = move(value.records[index].startDate)
            value.records[index].endDate = move(value.records[index].endDate)
        }
        return value
    }

    /// The answer a model would give, picked by the task named in the instructions.
    static func answer(instructions: String, input: String) -> String {
        if instructions.contains("收尾今天") {
            var items: [String] = []
            var inPending = false
            for line in input.components(separatedBy: "\n") {
                if line.hasPrefix("没做完的") { inPending = true; continue }
                if line.hasPrefix("最近的日子") { inPending = false }
                guard inPending, line.hasPrefix("["), let close = line.firstIndex(of: "]"),
                      let number = Int(line[line.index(after: line.startIndex)..<close]) else { continue }
                items.append(line.contains("已顺延")
                    ? #"{"index":\#(number),"action":"split","note":"已经顺延三天"}"#
                    : #"{"index":\#(number),"action":"tomorrow","note":""}"#)
            }
            return #"{"items":[\#(items.joined(separator: ","))]}"#
        }
        if instructions.contains("本周来信") {
            var numbers: [(Int, String)] = []
            for line in input.components(separatedBy: "\n") {
                guard line.hasPrefix("["), let close = line.firstIndex(of: "]"),
                      let number = Int(line[line.index(after: line.startIndex)..<close]) else { continue }
                numbers.append((number, line))
            }
            let a = numbers.first { $0.1.contains("待办") }?.0 ?? 1
            var b = numbers.first { $0.1.contains("汇报") || $0.1.contains("发布") }?.0 ?? 2
            if b == a { b = numbers.contains { $0.0 == a + 1 } ? a + 1 : 1 }
            return #"{"title":"卡在第一步的一周","body":"前半周很稳；周四起两件大事一直顺延，都卡在没拆开的第一步。","next_focus":"先把大事拆出能马上开始的第一步。","evidence":[{"point":"「归纳昨日待办」一直顺延","plans":[\#(a)]},{"point":"大事还没开始","plans":[\#(b)]}]}"#
        }
        if instructions.contains("排今天计划的先后") {
            let line = input.components(separatedBy: "\n").first { $0.contains("发布说明") } ?? ""
            let number = line.split(separator: "]").first.flatMap { Int($0.dropFirst()) } ?? 1
            return #"{"order":[\#(number)],"reason":"离项目交付只剩两天，先把说明定稿"}"#
        }
        if instructions.contains("拆出结构") {
            let parts = input.components(separatedBy: CharacterSet(charactersIn: "、，,")).map {
                $0.trimmingCharacters(in: .whitespaces)
            }.filter { !$0.isEmpty }
            if parts.count >= 2 {
                let items = parts.map { "\"\($0)\"" }.joined(separator: ",")
                return #"{"title":null,"date":null,"offset_days":null,"weekday":null,"week_offset":null,"part":null,"time":null,"minutes":null,"category":null,"repeat":null,"items":[\#(items)]}"#
            }
            return #"{"title":"交保险计划书","date":null,"offset_days":1,"weekday":null,"week_offset":null,"part":"exact","time":"15:00","minutes":45,"category":"学习","repeat":null,"items":[]}"#
        }
        if instructions.contains("拆成可以直接动手的小步骤") {
            return #"{"steps":[{"title":"列出第三节要补的两处论据","minutes":10},{"title":"查中出教授 2019 年论文的页码","minutes":20},{"title":"改写第三节第一段","minutes":40},{"title":"通读全文并核对字数","minutes":15}]}"#
        }
        if instructions.contains("整理成清单") {
            return #"{"items":["打印准考证","确认考场路线和到达时间","准备黑色签字笔两支","复习损害保险的三个判例"]}"#
        }
        if instructions.contains("准备转成计划的几行") {
            return #"{"rows":[{"index":0,"date":null,"offset_days":1,"weekday":null,"week_offset":null,"category":"学习"},{"index":1,"date":null,"offset_days":null,"weekday":5,"week_offset":0,"category":"工作"}]}"#
        }
        if instructions.contains("起草周复盘") {
            return #"{"note":"这周把复习按天排开后，前半周完成得比较稳；周四起两件大的计划一直顺延，卡在开头那一步没有拆开。","next_focus":"大的计划先拆出马上能开始的第一步。","evidence":[{"point":"前半周的计划都按时完成","plans":[1,2]},{"point":"顺延的计划都偏大","plans":[3]}]}"#
        }
        if instructions.contains("拟下周的计划草稿") {
            return #"{"plans":[{"title":"把顺延的计划拆出第一步","weekday":1,"part":"morning","minutes":25,"category":"学习","reason":"顺延最久"},{"title":"补完第三节论据","weekday":2,"part":"afternoon","minutes":60,"category":"学习","reason":"本周没做完"},{"title":"通读全文并核对字数","weekday":4,"part":"evening","minutes":30,"category":"学习","reason":"收尾"},{"title":"写下周复盘","weekday":7,"part":"evening","minutes":15,"category":null,"reason":"下周关注里提到"}]}"#
        }
        if instructions.contains("拟一份准备计划") {
            return #"{"plans":[{"title":"列出要准备的材料清单","days_before":2,"minutes":20,"category":"学习","reason":"先看清要做什么"},{"title":"完成第一轮准备","days_before":1,"minutes":90,"category":"学习","reason":"留出修改的时间"},{"title":"查漏补缺并模拟一遍","days_before":1,"minutes":60,"category":"学习","reason":"发现问题还来得及"},{"title":"确认时间、地点和要带的东西","days_before":0,"minutes":15,"category":null,"reason":"前一天核对"}]}"#
        }
        if instructions.contains("计划库的筛选条件") {
            return #"{"keyword":null,"category":"学习","status":"planned","period":"last_week","from":null,"to":null}"#
        }
        if instructions.contains("粘贴的一段文字"), input.contains("季度报告") {
            // ai-share: the shared sentence, so the drafts match what was shared.
            return #"{"plans":[{"title":"交季度报告","date":null,"offset_days":null,"weekday":5,"week_offset":0,"part":null,"time":null,"minutes":null,"category":"工作"},{"title":"和小王对一下数据","date":null,"offset_days":null,"weekday":3,"week_offset":0,"part":"afternoon","time":null,"minutes":30,"category":"工作"},{"title":"订会议室","date":null,"offset_days":1,"weekday":null,"week_offset":null,"part":"morning","time":null,"minutes":10,"category":"工作"}]}"#
        }
        if instructions.contains("粘贴的一段文字") {
            return #"{"plans":[{"title":"交志望理由書终稿","date":null,"offset_days":2,"weekday":null,"week_offset":null,"part":"exact","time":"17:00","minutes":null,"category":"学习"},{"title":"预约体检","date":null,"offset_days":null,"weekday":5,"week_offset":0,"part":"morning","time":null,"minutes":20,"category":null},{"title":"给老师回邮件确认面谈时间","date":null,"offset_days":0,"weekday":null,"week_offset":null,"part":null,"time":null,"minutes":10,"category":"学习"}]}"#
        }
        return #"{"ok":true}"#
    }
}

final class AIQAStubProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let url = request.url ?? URL(string: "https://qa.invalid")!
        let body = Self.bodyData(of: request)
        let delay = Double(ProcessInfo.processInfo.environment["MOJI_QA_AI_DELAY"] ?? "") ?? 0.5
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self else { return }
            let (status, data) = Self.response(path: url.path, body: body)
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: data)
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }

    static func response(path: String, body: Data) -> (Int, Data) {
        func json(_ object: Any) -> Data { (try? JSONSerialization.data(withJSONObject: object)) ?? Data() }
        if AIQAFixture.mode == "error" {
            return (401, json(["error": ["message": "Authentication Fails (QA fixture)"]]))
        }
        if path.hasSuffix("/models") {
            return (200, json(["data": [["id": "deepseek-flash"], ["id": "deepseek-v4-pro"], ["id": "deepseek-coder-2"]]]))
        }
        let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
        var instructions = root["system"] as? String ?? root["instructions"] as? String ?? ""
        var input = root["input"] as? String ?? ""
        for message in root["messages"] as? [[String: Any]] ?? [] {
            let content = message["content"] as? String ?? ""
            if message["role"] as? String == "system" { instructions = content } else { input = content }
        }
        let text = AIQAFixture.answer(instructions: instructions, input: input)
        if path.hasSuffix("/messages") {
            return (200, json(["content": [["type": "text", "text": text]], "stop_reason": "end_turn"]))
        }
        if path.hasSuffix("/responses") {
            return (200, json(["status": "completed",
                               "output": [["type": "message", "content": [["type": "output_text", "text": text]]]]]))
        }
        return (200, json(["choices": [["message": ["role": "assistant", "content": text], "finish_reason": "stop"]]]))
    }

    /// URLSession hands a protocol the body as a stream, not as `httpBody`.
    static func bodyData(of request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
#endif
