import Foundation

/// Sends one instruction and one piece of text to the provider in use and
/// returns the model's text. The request formats, limits and reasoning fields
/// follow the provider layer of 读屏.
struct AIClient {
    static let liveSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 25
        config.timeoutIntervalForResource = 90
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
#if DEBUG
        if AIQAFixture.isActive { config.protocolClasses = [AIQAStubProtocol.self] }
#endif
        return URLSession(configuration: config)
    }()

    let session: URLSession
    init(session: URLSession = AIClient.liveSession) { self.session = session }

    static func endpoint(configuration: ProviderConfiguration, models: Bool = false) throws -> URL {
        let input = configuration.baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: input),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else {
            throw AIRequestError.invalidURL
        }
        var path = components.path
        while path.hasSuffix("/") { path.removeLast() }
        // Accept either an API base URL or a full endpoint without appending it twice.
        for suffix in ["/chat/completions", "/responses", "/messages", "/models"] where path.hasSuffix(suffix) {
            path.removeLast(suffix.count)
            break
        }
        components.path = path + "/" + (models ? "models" : configuration.style.route)
        guard let url = components.url else { throw AIRequestError.invalidURL }
        return url
    }

    /// One request, one answer. `entryID` tells which provider the fields are for.
    func complete(instructions: String, input: String, entryID: String,
                  configuration: ProviderConfiguration, apiKey: String,
                  timeout: TimeInterval = 40) async throws -> String {
        let config = configuration.requestFields
        let model = config.model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !model.isEmpty else { throw AIRequestError.missingModel }
        var request = try authorizedRequest(configuration: config, apiKey: apiKey, timeout: timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: Self.body(instructions: instructions, input: input,
                                      provider: AIProvider.kind(of: entryID), configuration: config, model: model))
        return try Self.parseText(try await perform(request), style: config.style)
    }

    static func body(instructions: String, input: String, provider: AIProvider,
                     configuration config: ProviderConfiguration, model: String) -> [String: Any] {
        var body: [String: Any] = ["model": model]
        switch config.style {
        case .anthropicMessages:
            body["system"] = instructions
            body["max_tokens"] = 4_096
            body["messages"] = [["role": "user", "content": input]]
        case .responses:
            body["instructions"] = instructions
            body["input"] = input
            body["store"] = false
        case .chatCompletions:
            body["messages"] = [["role": "system", "content": instructions], ["role": "user", "content": input]]
        }
        applyOutputLimits(to: &body, provider: provider, configuration: config, model: model)
        applyReasoning(to: &body, provider: provider, configuration: config, model: model)
        return body
    }

    /// Fields a known model needs to return a whole answer, whatever the
    /// reasoning setting. Unknown models and other interface formats get nothing.
    static func applyOutputLimits(to body: inout [String: Any], provider: AIProvider,
                                  configuration config: ProviderConfiguration, model: String) {
        guard config.style == .chatCompletions else { return }
        let id = model.lowercased()
        switch provider {
        case .doubao where id.hasPrefix("doubao-seed-2-1"):
            // Without a limit the answer stops at 4K tokens.
            body["max_tokens"] = 16_000
        case .kimi where id.hasPrefix("kimi-k3"):
            // Reasoning counts toward the limit; Kimi's documentation asks for 16000 or more.
            body["max_tokens"] = 16_000
        case .minimax where id.hasPrefix("minimax-m"):
            // Keeps the model's thinking out of the answer.
            body["reasoning_split"] = true
            if id.hasPrefix("minimax-m3") { body["max_completion_tokens"] = 32_000 }
        default:
            break
        }
    }

    /// Reasoning fields. `nil` (自动) keeps reasoning low for known model
    /// families, since every task here is short, and otherwise sends nothing;
    /// an explicit choice is sent in the provider's own format, and an
    /// unsupported choice surfaces as the provider's error message.
    static func applyReasoning(to body: inout [String: Any], provider: AIProvider,
                               configuration config: ProviderConfiguration, model: String) {
        guard let effort = config.reasoning, provider.reasoningOptions.contains(effort) else {
            applyAutomaticReasoning(to: &body, provider: provider, configuration: config, model: model)
            return
        }
        let off = effort == .off
        let level = effort.rawValue
        if provider == .qwen {
            if off, config.style != .anthropicMessages { body["enable_thinking"] = false }
            return
        }
        switch config.style {
        case .responses:
            body["reasoning"] = ["effort": off ? "none" : level]
        case .chatCompletions:
            switch provider {
            case .deepSeek, .doubao:
                body["thinking"] = ["type": off ? "disabled" : "enabled"]
                if !off { body["reasoning_effort"] = level }
            case .gemini:
                // Gemini 2.x Flash can turn thinking off; later models only go down to minimal.
                body["reasoning_effort"] = off ? (model.hasPrefix("gemini-2") ? "none" : "minimal") : level
            case .kimi:
                // K3 always thinks and takes low, high or max.
                body["reasoning_effort"] = threeStepEffort(effort)
            case .zhipu:
                let id = model.lowercased()
                if id.hasPrefix("glm-5.3") {
                    // Thinking cannot be turned off; these models take low, high or max.
                    body["reasoning_effort"] = threeStepEffort(effort)
                } else {
                    body["thinking"] = ["type": off ? "disabled" : "enabled"]
                    // Earlier models switch thinking on or off and have no effort level.
                    if !off, id.hasPrefix("glm-5.2") { body["reasoning_effort"] = level }
                }
            case .minimax:
                if model.lowercased().hasPrefix("minimax-m3.1") {
                    // Thinking is always on for this model; only the effort goes down.
                    body["reasoning_effort"] = off ? "low" : level
                } else {
                    // Other models think or do not, and ignore an effort level.
                    body["thinking"] = ["type": off ? "disabled" : "adaptive"]
                }
            default:
                body["reasoning_effort"] = off ? "none" : level
            }
        case .anthropicMessages:
            if provider == .deepSeek {
                body["thinking"] = ["type": off ? "disabled" : "enabled"]
                if !off { body["output_config"] = ["effort": level] }
            } else {
                applyClaudeThinking(to: &body, effort: effort, model: model)
            }
        }
    }

    /// 关闭 and 低 share the lowest step on models that take only low, high and max.
    private static func threeStepEffort(_ effort: ReasoningEffort) -> String {
        switch effort {
        case .off, .low: return "low"
        case .medium: return "high"
        case .high: return "max"
        }
    }

    private static func applyClaudeThinking(to body: inout [String: Any], effort: ReasoningEffort, model: String) {
        let id = model.lowercased()
        // Older models only understand a fixed thinking budget and do not think unless asked.
        let budgetPrefixes = ["claude-3", "claude-haiku-4", "claude-sonnet-4-5", "claude-sonnet-4-0", "claude-sonnet-4-2",
                              "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5", "claude-opus-4-2"]
        if budgetPrefixes.contains(where: { id.hasPrefix($0) }) || id == "claude-sonnet-4" || id == "claude-opus-4" {
            let budget: Int
            switch effort {
            case .off: return
            case .low: budget = 1_024
            case .medium: budget = 4_096
            case .high: budget = 8_192
            }
            // Thinking counts toward max_tokens; keep the answer's own room.
            body["thinking"] = ["type": "enabled", "budget_tokens": budget]
            body["max_tokens"] = 4_096 + budget
            return
        }
        if effort == .off {
            if id.hasPrefix("claude-sonnet-5-5") {
                body["thinking"] = ["type": "between_tools"]
            } else if ["claude-opus-5-5", "claude-fable", "claude-mythos"].contains(where: { id.hasPrefix($0) }) {
                // Thinking cannot be turned off on these models; use the lowest effort.
                body["output_config"] = ["effort": "low"]
            } else {
                body["thinking"] = ["type": "disabled"]
            }
            return
        }
        body["thinking"] = ["type": "adaptive"]
        body["output_config"] = ["effort": effort.rawValue]
        body["max_tokens"] = 16_000
    }

    /// 自动: only documented fields for known model families; custom endpoints get nothing.
    private static func applyAutomaticReasoning(to body: inout [String: Any], provider: AIProvider,
                                                configuration config: ProviderConfiguration, model: String) {
        let id = model.lowercased()
        if provider == .deepSeek, ["deepseek-flash", "deepseek-v4-pro"].contains(model) {
            // DeepSeek defaults to high reasoning, which is slow for a short task.
            if config.style == .responses { body["reasoning"] = ["effort": "none"] }
            else { body["thinking"] = ["type": "disabled"] }
        }
        if provider == .openAI {
            let efforts = ["gpt-6-luna": "none", "gpt-6-sol": "none", "gpt-6.1-sol": "low", "gpt-6-astra": "low"]
            if let effort = efforts[model] {
                if config.style == .responses { body["reasoning"] = ["effort": effort] }
                else if config.style == .chatCompletions { body["reasoning_effort"] = effort }
            }
        }
        if provider == .claude, config.style == .anthropicMessages,
           ["claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos"].contains(where: { id.hasPrefix($0) }) {
            // The 5 series thinks adaptively by default. Low effort keeps a short
            // task short; thinking stays available.
            body["output_config"] = ["effort": "low"]
        }
        guard config.style == .chatCompletions else { return }
        switch provider {
        case .gemini where id.hasPrefix("gemini-2.5-flash"):
            // 2.5 Flash and Flash-Lite can turn thinking off.
            body["reasoning_effort"] = "none"
        case .gemini where id.contains("flash") && (id.hasPrefix("gemini-3") || id.hasPrefix("gemini-4")):
            // Thinking cannot be turned off on later models; minimal is their lowest level.
            body["reasoning_effort"] = "minimal"
        case .doubao where id.hasPrefix("doubao-seed-2-1"), .zhipu where id.hasPrefix("glm-5.2"):
            body["thinking"] = ["type": "disabled"]
        case .kimi where id.hasPrefix("kimi-k3"), .zhipu where id.hasPrefix("glm-5.3"),
             .minimax where id.hasPrefix("minimax-m3.1"):
            // Thinking cannot be turned off here.
            body["reasoning_effort"] = "low"
        case .minimax where id == "minimax-m3":
            body["thinking"] = ["type": "disabled"]
        default:
            break
        }
    }

    /// The model ids the account can use, as the provider lists them.
    func models(configuration: ProviderConfiguration, apiKey: String) async throws -> [String] {
        let request = try authorizedRequest(configuration: configuration, apiKey: apiKey, timeout: 15, models: true)
        let data = try await perform(request)
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = root["data"] as? [[String: Any]] else {
            throw AIRequestError.malformedResponse
        }
        let models = Array(Set(items.compactMap { $0["id"] as? String })).sorted()
        guard !models.isEmpty else { throw AIRequestError.emptyResponse }
        return models
    }

    private func authorizedRequest(configuration: ProviderConfiguration, apiKey: String,
                                   timeout: TimeInterval, models: Bool = false) throws -> URLRequest {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw AIRequestError.missingKey }
        guard !key.contains("\n"), !key.contains("\r") else { throw AIRequestError.credentialUnavailable }
        var request = URLRequest(url: try Self.endpoint(configuration: configuration, models: models))
        request.timeoutInterval = timeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if configuration.style == .anthropicMessages {
            request.setValue(key, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        } else {
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        do {
            try Task.checkCancellation()
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse else { throw AIRequestError.malformedResponse }
            guard (200..<300).contains(http.statusCode) else {
                throw AIProviderErrorMessage.error(status: http.statusCode, body: data, secrets: Self.secrets(of: request))
            }
            guard data.count <= 2_000_000 else { throw AIRequestError.malformedResponse }
            return data
        } catch is CancellationError { throw CancellationError() }
        catch let error as AIRequestError { throw error }
        catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw error.code == .timedOut ? AIRequestError.timeout : AIRequestError.network
        } catch { throw AIRequestError.network }
    }

    /// The credential sent with a request, so an echoed key is never shown.
    private static func secrets(of request: URLRequest) -> [String] {
        var values: [String] = []
        if let key = request.value(forHTTPHeaderField: "x-api-key") { values.append(key) }
        if let auth = request.value(forHTTPHeaderField: "Authorization") {
            values.append(auth.hasPrefix("Bearer ") ? String(auth.dropFirst(7)) : auth)
        }
        return values
    }

    static func parseText(_ data: Data, style: APIStyle) throws -> String {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AIRequestError.malformedResponse
        }
        var text: String
        switch style {
        case .anthropicMessages:
            guard let content = root["content"] as? [[String: Any]] else { throw AIRequestError.malformedResponse }
            text = content.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined(separator: "\n")
            if root["stop_reason"] as? String == "max_tokens" { throw AIRequestError.incompleteResponse }
            if root["stop_reason"] as? String == "refusal" { throw AIRequestError.emptyResponse }
        case .chatCompletions:
            guard let choices = root["choices"] as? [[String: Any]], let first = choices.first,
                  let message = first["message"] as? [String: Any] else {
                throw AIRequestError.malformedResponse
            }
            if let content = message["content"] as? String { text = content }
            else if let content = message["content"] as? [[String: Any]] {
                text = content.compactMap { $0["text"] as? String }.joined(separator: "\n")
            } else { text = "" }
            if first["finish_reason"] as? String == "length" { throw AIRequestError.incompleteResponse }
        case .responses:
            guard let output = root["output"] as? [[String: Any]] else { throw AIRequestError.malformedResponse }
            // A reasoning item may precede the output message; never assume output[0] is text.
            text = output.filter { $0["type"] as? String == "message" }
                .flatMap { $0["content"] as? [[String: Any]] ?? [] }
                .filter { $0["type"] as? String == "output_text" }
                .compactMap { $0["text"] as? String }
                .joined(separator: "\n")
            if root["status"] as? String == "incomplete" { throw AIRequestError.incompleteResponse }
            if root["status"] as? String == "failed" { throw AIRequestError.emptyResponse }
        }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw AIRequestError.emptyResponse }
        return text
    }
}

/// Reads the human-readable message from a provider's JSON error body.
/// Only structured message fields are used: a raw or non-JSON body is never
/// shown, and anything that looks like a credential is masked.
enum AIProviderErrorMessage {
    static let maximumLength = 200

    static func extract(_ data: Data, secrets: [String] = []) -> String? {
        guard !data.isEmpty, data.count <= 65_536,
              let json = try? JSONSerialization.jsonObject(with: data) else { return nil }
        // Gemini's OpenAI-compatible endpoint may wrap the error in an array.
        let root = (json as? [Any])?.first ?? json
        guard let object = root as? [String: Any] else { return nil }
        var message: String?
        if let error = object["error"] as? [String: Any] {
            message = error["message"] as? String ?? error["msg"] as? String
        } else if let error = object["error"] as? String {
            message = error
        }
        if message == nil {
            message = object["message"] as? String ?? object["detail"] as? String ?? object["msg"] as? String
        }
        guard let message else { return nil }
        return sanitized(message, secrets: secrets)
    }

    static func sanitized(_ message: String, secrets: [String] = []) -> String? {
        var text = message
        for secret in secrets where secret.count >= 8 {
            text = text.replacingOccurrences(of: secret, with: "••••")
        }
        // Common key and token shapes, including partially masked echoes.
        let patterns = [#"(?i)bearer\s+\S+"#, #"sk-[A-Za-z0-9*_\-]{6,}"#, #"AIza[0-9A-Za-z_\-]{10,}"#,
                        #"(?i)(api[_-]?key|token|secret)(["'\s:=]+)[^\s,"'}]{6,}"#]
        for pattern in patterns {
            let template = pattern.contains("(api") ? "$1$2••••" : "••••"
            text = text.replacingOccurrences(of: pattern, with: template, options: .regularExpression)
        }
        text = String(text.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) ? " " : Character($0) })
        text = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if text.count > maximumLength { text = String(text.prefix(maximumLength)) + "…" }
        return text
    }

    static func error(status: Int, body: Data, secrets: [String]) -> AIRequestError {
        if let detail = extract(body, secrets: secrets) { return .httpDetail(status, detail) }
        return .http(status)
    }
}
