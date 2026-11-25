import Foundation

enum APIStyle: String, Codable, CaseIterable, Identifiable {
    case chatCompletions, responses, anthropicMessages
    var id: String { rawValue }
    var title: String {
        switch self { case .responses: return "Responses"; case .chatCompletions: return "Chat Completions"; case .anthropicMessages: return "Claude Messages" }
    }
    /// Name on the model page, in the words provider documents use.
    var displayName: String {
        switch self { case .chatCompletions: return "OpenAI 兼容"; case .responses: return "OpenAI Responses"; case .anthropicMessages: return "Claude 兼容" }
    }
    var route: String {
        switch self { case .responses: return "responses"; case .chatCompletions: return "chat/completions"; case .anthropicMessages: return "messages" }
    }
}

enum AIProvider: String, Codable, CaseIterable, Identifiable {
    case openAI, deepSeek, gemini, claude, qwen, custom
    case doubao, kimi, zhipu, minimax
    var id: String { rawValue }

    /// Order on the 添加供应商 page: Claude, ChatGPT and DeepSeek first, then
    /// the rest, with no domestic/overseas split.
    static let presets: [AIProvider] = [.claude, .openAI, .deepSeek, .qwen, .doubao, .kimi, .zhipu, .minimax, .gemini]
    /// A built-in provider's entry uses its raw value as id; any other id is a custom service.
    static func kind(of entryID: String) -> AIProvider { AIProvider(rawValue: entryID) ?? .custom }

    var title: String {
        switch self {
        case .openAI: return "ChatGPT"; case .deepSeek: return "DeepSeek"
        case .gemini: return "Gemini"; case .claude: return "Claude"
        case .qwen: return "通义千问"; case .custom: return "自定义 API"
        case .doubao: return "豆包"; case .kimi: return "Kimi"
        case .zhipu: return "智谱 GLM"; case .minimax: return "MiniMax"
        }
    }
    /// Who runs the service; the second line of a provider row.
    var subtitle: String {
        switch self {
        case .openAI: return "OpenAI"; case .deepSeek: return "深度求索"
        case .gemini: return "Google"; case .claude: return "Anthropic"
        case .qwen: return "阿里云百炼"; case .custom: return "OpenAI 或 Claude 兼容接口"
        case .doubao: return "火山方舟"; case .kimi: return "月之暗面"
        case .zhipu: return "智谱 AI"; case .minimax: return "稀宇科技"
        }
    }
    var isRecommended: Bool { self == .deepSeek }
    var defaultURL: String {
        switch self {
        case .openAI: return "https://api.openai.com/v1"
        case .deepSeek: return "https://api.deepseek.com"
        case .gemini: return "https://generativelanguage.googleapis.com/v1beta/openai"
        case .claude: return "https://api.anthropic.com/v1"
        case .qwen: return "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
        case .custom: return ""
        case .doubao: return "https://ark.cn-beijing.volces.com/api/v3"
        case .kimi: return "https://api.moonshot.cn/v1"
        case .zhipu: return "https://open.bigmodel.cn/api/paas/v4"
        case .minimax: return "https://api.minimax.cn/v1"
        }
    }
    var defaultStyle: APIStyle {
        self == .openAI ? .responses : (self == .claude ? .anthropicMessages : .chatCompletions)
    }
    var models: [ModelPreset] {
        switch self {
        case .openAI: return [.init("gpt-6-luna", "GPT-6 Luna", "日常够用"), .init("gpt-6.1-sol", "GPT-6.1 Sol", "更细致"), .init("gpt-4.1-mini", "GPT-4.1 Mini", "轻量")]
        case .deepSeek: return [.init("deepseek-flash", "DeepSeek Flash", "日常够用"), .init("deepseek-v4-pro", "DeepSeek V4 Pro", "更细致")]
        case .gemini: return [.init("gemini-3.8-flash", "Gemini 3.8 Flash", "日常够用"), .init("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "轻量")]
        case .claude: return [.init("claude-haiku-5-5", "Claude Haiku 5.5", "日常够用"), .init("claude-sonnet-5-5", "Claude Sonnet 5.5", "更细致"), .init("claude-opus-5-5", "Claude Opus 5.5", "最细致")]
        case .qwen: return [.init("qwen-plus", "Qwen Plus", "日常够用"), .init("qwen-turbo", "Qwen Turbo", "轻量"), .init("qwen-max", "Qwen Max", "更细致")]
        case .custom: return []
        case .doubao: return [.init("doubao-seed-2-1-lite-260915", "豆包 Seed 2.1 Lite", "日常够用"), .init("doubao-seed-2-1-pro-260915", "豆包 Seed 2.1 Pro", "更细致")]
        case .kimi: return [.init("kimi-k3.1-pro", "Kimi K3.1 Pro", "日常够用"), .init("kimi-k3.1-pro-highspeed", "Kimi K3.1 Pro 高速版", "更快")]
        case .zhipu: return [.init("glm-5.3-flash", "GLM-5.3 Flash", "日常够用"), .init("glm-5.3", "GLM-5.3", "更细致")]
        case .minimax: return [.init("MiniMax-M3.1-Flash", "MiniMax M3.1 Flash", "日常够用"), .init("MiniMax-M3", "MiniMax M3", "更细致")]
        }
    }
    /// What to know about the address; shown under the key and address fields.
    var setupNote: String {
        switch self {
        case .qwen: return "默认是美国区域的地址，密钥要和区域一致。其他区域请把 API 地址改成控制台给出的地址。"
        case .doubao: return "默认是火山方舟（中国站）的地址。国际版 BytePlus 的地址和模型 ID 都不同，要用它请改 API 地址并手动添加模型。"
        case .kimi: return "默认是国内站的地址。国际站的密钥不通用，要用它请把 API 地址改成 https://api.moonshot.ai/v1。"
        case .zhipu: return "默认是国内站的地址。国际站 Z.AI 的密钥不通用，要用它请把 API 地址改成 https://api.z.ai/api/paas/v4。"
        case .minimax: return "默认是国内站的地址。国际站请把 API 地址改成 https://api.minimax.io/v1。"
        case .openAI, .deepSeek, .gemini, .claude: return "已填入官方地址，一般不用改。"
        case .custom: return "填服务商给的 API 地址，一般以 /v1 结尾。接口格式不确定就保持默认。"
        }
    }
    var documentationURL: URL {
        let url: String
        switch self {
        case .openAI: url = "https://developers.openai.com/api/docs/models"
        case .deepSeek: url = "https://api-docs.deepseek.com/"
        case .gemini: url = "https://ai.google.dev/gemini-api/docs/openai"
        case .claude: url = "https://platform.claude.com/docs/en/models/overview"
        case .qwen: url = "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope"
        case .custom: url = "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create"
        case .doubao: url = "https://docs.volcengine.com/docs/82379"
        case .kimi: url = "https://platform.kimi.com/docs"
        case .zhipu: url = "https://docs.bigmodel.cn"
        case .minimax: url = "https://platform.minimax.cn/docs"
        }
        return URL(string: url)!
    }
    /// The console page where a key is created, when the provider has a stable one.
    var keyURL: URL? {
        let url: String
        switch self {
        case .openAI: url = "https://platform.openai.com/api-keys"
        case .deepSeek: url = "https://platform.deepseek.com/api_keys"
        case .gemini: url = "https://aistudio.google.com/apikey"
        case .claude: url = "https://platform.claude.com/settings/keys"
        case .doubao: url = "https://ark.volcengine.com/region:cn-beijing/apiKey"
        case .kimi: url = "https://platform.kimi.com/console/api-keys"
        case .zhipu: url = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
        case .minimax: url = "https://platform.minimax.cn/user-center/basic-information/interface-key"
        // 通义千问's console differs by region; the documentation link covers it.
        case .qwen, .custom: return nil
        }
        return URL(string: url)
    }
}

struct ModelPreset: Identifiable {
    var id: String
    var title: String
    var detail: String
    init(_ id: String, _ title: String, _ detail: String) { self.id = id; self.title = title; self.detail = detail }
}

/// User-selected reasoning depth. `nil` in a configuration means 自动: keep
/// the built-in, provider-specific behaviour and send nothing new.
enum ReasoningEffort: String, Codable, CaseIterable, Identifiable, Sendable {
    case off, low, medium, high
    var id: String { rawValue }
    var title: String {
        switch self { case .off: return "关闭"; case .low: return "低"; case .medium: return "中"; case .high: return "高" }
    }
}

extension AIProvider {
    /// 通义千问 only enables thinking in streaming mode, so it offers 关闭 only.
    var reasoningOptions: [ReasoningEffort] { self == .qwen ? [.off] : ReasoningEffort.allCases }
    var reasoningNote: String {
        switch self {
        case .qwen: return "通义千问的思考模式需要流式输出，这里只提供关闭。"
        case .kimi: return "K3 系列始终思考，无法关闭：自动和“关闭”都用最低档。"
        case .zhipu: return "GLM-5.3 系列无法关闭思考，自动和“关闭”都用最低档；GLM-5.2 可以关闭。"
        case .minimax: return "M3.1 Flash 无法关闭思考，自动和“关闭”都用最低档；M3 只能开或关，不分强度。"
        case .custom: return "自动不发送任何推理参数；其他档位按所选接口格式发送标准字段。"
        case .openAI, .deepSeek, .gemini, .claude, .doubao:
            return "自动：墨记的整理都是短任务，对已知模型关闭思考或用最低档。"
        }
    }
}

/// One model kept under a provider, with the settings that belong to it.
struct SavedModel: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var style: APIStyle
    var reasoning: ReasoningEffort? = nil
    init(id: String, style: APIStyle, reasoning: ReasoningEffort? = nil) {
        self.id = id; self.style = style; self.reasoning = reasoning
    }
    private enum CodingKeys: String, CodingKey { case id, style, reasoning }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        style = try values.decode(APIStyle.self, forKey: .style)
        reasoning = (try? values.decodeIfPresent(ReasoningEffort.self, forKey: .reasoning)) ?? nil
    }
}

/// One provider entry. `model`, `style` and `reasoning` describe the model the
/// provider currently uses, which is what a request reads; `models` keeps the
/// other models and their own settings.
struct ProviderConfiguration: Codable, Equatable {
    var baseURL: String
    var model: String
    var style: APIStyle
    var reasoning: ReasoningEffort? = nil
    /// The user's own name for a custom service.
    var name: String? = nil
    /// `nil` until the list is first edited; the provider's presets stand in,
    /// so settings saved by earlier versions load without a migration.
    var models: [SavedModel]? = nil
    init(provider: AIProvider) {
        baseURL = provider.defaultURL
        model = provider.models.first?.id ?? ""
        style = provider.defaultStyle
    }
    private enum CodingKeys: String, CodingKey { case baseURL, model, style, reasoning, name, models }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        baseURL = try values.decode(String.self, forKey: .baseURL)
        model = try values.decode(String.self, forKey: .model)
        style = try values.decode(APIStyle.self, forKey: .style)
        // Written by a newer build: fall back to 自动.
        reasoning = (try? values.decodeIfPresent(ReasoningEffort.self, forKey: .reasoning)) ?? nil
        name = (try? values.decodeIfPresent(String.self, forKey: .name)) ?? nil
        models = (try? values.decodeIfPresent([SavedModel].self, forKey: .models)) ?? nil
    }
}

extension ProviderConfiguration {
    private var selectedID: String { model.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The fields a request depends on, without the name and the other models.
    var requestFields: ProviderConfiguration {
        var copy = self
        copy.name = nil; copy.models = nil
        return copy
    }

    /// Every model kept under this provider. The one in use carries the
    /// top-level settings, so code that edits `model` or `style` directly stays valid.
    func savedModels(for provider: AIProvider) -> [SavedModel] {
        var list: [SavedModel] = []
        for item in models ?? provider.models.map({ SavedModel(id: $0.id, style: style) }) {
            let id = item.id.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !list.contains(where: { $0.id == id }) else { continue }
            list.append(SavedModel(id: id, style: item.style, reasoning: item.reasoning))
        }
        let selected = selectedID
        guard !selected.isEmpty else { return list }
        let current = SavedModel(id: selected, style: style, reasoning: reasoning)
        if let index = list.firstIndex(where: { $0.id == selected }) { list[index] = current }
        else { list.insert(current, at: 0) }
        return list
    }

    /// Switches the provider to one of its kept models.
    mutating func select(_ id: String, provider: AIProvider) {
        let list = savedModels(for: provider)
        guard let item = list.first(where: { $0.id == id }) else { return }
        models = list
        model = item.id; style = item.style; reasoning = item.reasoning
    }

    /// Adds a model, or replaces the settings of one that is already kept.
    mutating func upsert(_ item: SavedModel, provider: AIProvider) {
        let id = item.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        var list = savedModels(for: provider)
        let clean = SavedModel(id: id, style: item.style, reasoning: item.reasoning)
        if let index = list.firstIndex(where: { $0.id == id }) { list[index] = clean } else { list.append(clean) }
        models = list
        if selectedID.isEmpty || selectedID == id { model = id; style = clean.style; reasoning = clean.reasoning }
    }

    /// Removes a model. When it was the one in use, the next kept model takes over.
    mutating func removeModel(_ id: String, provider: AIProvider) {
        var list = savedModels(for: provider)
        list.removeAll { $0.id == id }
        models = list
        guard selectedID == id else { return }
        if let next = list.first { model = next.id; style = next.style; reasoning = next.reasoning }
        else { model = ""; reasoning = nil }
    }
}

/// What 设置 › AI 模型 saves. It lives in the app's own defaults, outside
/// `PlanSnapshot`, so it never travels with a backup file or reaches the widget.
struct AISettings: Codable, Equatable {
    /// Off until the user turns it on; while off the app looks and behaves as before.
    var isEnabled = false
    /// Whether a plan typed into the home page's quick field is read for a
    /// time, a length or a type: the chips under the field are worked out on
    /// the device; the reading after it is added only happens while ready.
    var readsQuickEntry = true
    /// Entry points stay in place while AI is off; tapping one explains how to turn it on.
    var showsEntriesWhenOff = true
    /// 今日提要 above today's list.
    var dailyBrief = true
    /// The vermilion line under a plan carried three days or more.
    var carryOverNotes = true
    /// 晚间收尾 above today's list after 21:00.
    var eveningWrap = true
    /// 本周来信 on the 回顾 page, with its Sunday 20:00 notification.
    var weeklyLetter = true
    var activeID: String = AIProvider.deepSeek.rawValue
    /// Entries under 供应商, in order. `nil` until the list is first edited.
    var providerList: [String]? = nil
    var configurations: [String: ProviderConfiguration] = [:]

    init() {}

    private enum CodingKeys: String, CodingKey {
        case isEnabled, readsQuickEntry, showsEntriesWhenOff, dailyBrief, carryOverNotes, eveningWrap, weeklyLetter
        case activeID, providerList, configurations
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        isEnabled = (try? values.decodeIfPresent(Bool.self, forKey: .isEnabled)) ?? false
        readsQuickEntry = (try? values.decodeIfPresent(Bool.self, forKey: .readsQuickEntry)) ?? true
        showsEntriesWhenOff = (try? values.decodeIfPresent(Bool.self, forKey: .showsEntriesWhenOff)) ?? true
        dailyBrief = (try? values.decodeIfPresent(Bool.self, forKey: .dailyBrief)) ?? true
        carryOverNotes = (try? values.decodeIfPresent(Bool.self, forKey: .carryOverNotes)) ?? true
        eveningWrap = (try? values.decodeIfPresent(Bool.self, forKey: .eveningWrap)) ?? true
        weeklyLetter = (try? values.decodeIfPresent(Bool.self, forKey: .weeklyLetter)) ?? true
        activeID = (try? values.decodeIfPresent(String.self, forKey: .activeID)) ?? AIProvider.deepSeek.rawValue
        providerList = (try? values.decodeIfPresent([String].self, forKey: .providerList)) ?? nil
        configurations = (try? values.decodeIfPresent([String: ProviderConfiguration].self, forKey: .configurations)) ?? [:]
        if activeID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { activeID = AIProvider.deepSeek.rawValue }
    }

    var provider: AIProvider { AIProvider.kind(of: activeID) }

    var configuration: ProviderConfiguration {
        get { configurations[activeID] ?? ProviderConfiguration(provider: provider) }
        set { configurations[activeID] = newValue }
    }
}

// MARK: - Provider entries

extension AISettings {
    func configuration(for entryID: String) -> ProviderConfiguration {
        configurations[entryID] ?? ProviderConfiguration(provider: AIProvider.kind(of: entryID))
    }

    /// The user's own name for a custom service, otherwise the provider's name.
    func displayName(for entryID: String) -> String {
        let name = configurations[entryID]?.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? AIProvider.kind(of: entryID).title : name
    }

    /// Shown on the settings row, e.g. "DeepSeek · deepseek-flash".
    var engineLabel: String { "\(displayName(for: activeID)) · \(configuration.model)" }

    func savedModels(for entryID: String) -> [SavedModel] {
        configuration(for: entryID).savedModels(for: AIProvider.kind(of: entryID))
    }

    /// Entries under 供应商: the saved list, or before it exists the entry in
    /// use and those in `keyed`, the entries that have an API key.
    func listedProviderIDs(keyed: Set<String>) -> [String] {
        var list: [String] = []
        func add(_ id: String) { if !id.isEmpty, !list.contains(id) { list.append(id) } }
        if let providerList {
            providerList.forEach(add)
        } else {
            for provider in AIProvider.presets + [.custom]
            where provider.rawValue == activeID || keyed.contains(provider.rawValue) {
                add(provider.rawValue)
            }
        }
        add(activeID)
        return list
    }

    /// Adds an entry and returns its id. A built-in provider has one entry;
    /// custom services can have several.
    @discardableResult
    mutating func addProvider(_ kind: AIProvider, configuration: ProviderConfiguration, keyed: Set<String>,
                              newID: () -> String = { "custom-" + UUID().uuidString.lowercased() }) -> String {
        var list = listedProviderIDs(keyed: keyed)
        let id = kind == .custom && list.contains(kind.rawValue) ? newID() : kind.rawValue
        if !list.contains(id) { list.append(id) }
        providerList = list
        configurations[id] = configuration
        return id
    }

    /// Removes an entry and its settings. The entry in use cannot be removed.
    @discardableResult
    mutating func removeProvider(_ entryID: String, keyed: Set<String>) -> Bool {
        guard entryID != activeID else { return false }
        providerList = listedProviderIDs(keyed: keyed).filter { $0 != entryID }
        configurations.removeValue(forKey: entryID)
        return true
    }

    /// Makes an entry the one in use, optionally switching it to one of its models.
    mutating func activate(_ entryID: String, model: String? = nil) {
        activeID = entryID
        guard let model else { return }
        updateConfiguration(for: entryID) { $0.select(model, provider: AIProvider.kind(of: entryID)) }
    }

    mutating func updateConfiguration(for entryID: String, _ change: (inout ProviderConfiguration) -> Void) {
        var value = configuration(for: entryID)
        change(&value)
        configurations[entryID] = value
    }

    /// True for the one model requests currently use.
    func isInUse(model: String, of entryID: String) -> Bool {
        entryID == activeID && configuration.model.trimmingCharacters(in: .whitespacesAndNewlines) == model
    }
}

enum AIRequestError: LocalizedError, Equatable {
    case disabled, missingKey, missingModel, invalidURL
    case tooMuchText, emptyText
    case malformedResponse, emptyResponse, incompleteResponse
    /// The model answered, but not in the shape the task asked for.
    case unreadableAnswer
    case http(Int), network, timeout, credentialUnavailable, persistence
    /// An HTTP failure with the provider's own (sanitised, shortened) message.
    case httpDetail(Int, String)

    var errorDescription: String? {
        switch self {
        case .disabled: return "还没有开启。请到“设置 › AI 模型”打开开关。"
        case .missingKey: return "还没有填 API Key。请到“设置 › AI 模型”给正在用的供应商填写。"
        case .missingModel: return "还没有选模型。请到“设置 › AI 模型”选一个。"
        case .invalidURL: return "请使用完整的 HTTPS 地址，不要附加账号、查询参数或片段。"
        case .tooMuchText: return "内容太长了，请缩短到 6,000 字以内再试。"
        case .emptyText: return "没有可整理的内容。"
        case .malformedResponse: return "接口返回格式不兼容。请检查接口格式、API 地址和模型名称。"
        case .emptyResponse: return "模型没有返回内容。请换一个模型，或稍后重试。"
        case .incompleteResponse: return "模型的回答被提前截断了。请重试或换一个模型。"
        case .unreadableAnswer: return "模型没有按要求的格式回答，这次没有生成草稿。请重试或换一个模型。"
        case .http(let status): return Self.httpMessage(status)
        case .httpDetail(let status, let detail): return Self.httpMessage(status) + "\n服务商原话：\(detail)"
        case .network: return "无法连接模型服务，请检查网络和 API 地址。"
        case .timeout: return "等待超时。请稍后重试，或选择响应更快的模型。"
        case .credentialUnavailable: return "无法访问安全存储。请解锁手机后重试，或重新保存 API Key。"
        case .persistence: return "无法保存设置，请检查设备的可用空间。"
        }
    }

    private static func httpMessage(_ status: Int) -> String {
        switch status {
        case 401: return "API Key 无效或已过期，请检查设置。（HTTP 401）"
        case 402: return "API 账户余额不足，请检查服务商账户。（HTTP 402）"
        case 403: return "此账户无权使用该服务或模型。（HTTP 403）"
        case 404: return "接口或模型不存在，请检查 API 地址和模型名称。（HTTP 404）"
        case 429: return "请求过于频繁或额度不足，请稍后重试。（HTTP 429）"
        case 500...599: return "模型服务暂时不可用，请稍后重试。（HTTP \(status)）"
        default: return "请求被接口拒绝，请检查模型、接口格式及账户权限。（HTTP \(status)）"
        }
    }
}
