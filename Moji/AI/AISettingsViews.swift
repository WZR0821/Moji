import SwiftUI

// The pages under 设置 › AI 模型. Their structure follows the model pages of
// 读屏 (正在用 → 供应商 → 模型), drawn with Moji's paper, ink and seals.

// MARK: - Shared pieces

extension AISettingsStore {
    /// Applies a change and returns the reason when it could not be saved.
    @discardableResult
    func tryChange(keys: [String: String] = [:], _ change: (inout AISettings) -> Void) -> String? {
        do { try self.change(keys: keys, change); return nil } catch { return error.draftMessage }
    }
}

enum AIConnectionCheck {
    /// Sends one short request and returns how long the answer took.
    @MainActor
    static func run(entryID: String, configuration: ProviderConfiguration, apiKey: String) async throws -> Double {
        let start = Date()
        _ = try await AIClient().complete(instructions: "只回复一个“好”字。", input: "你好", entryID: entryID,
                                          configuration: configuration, apiKey: apiKey, timeout: 15)
        return Date().timeIntervalSince(start)
    }
}

enum AICheckState: Equatable {
    case idle, running, passed(Double), failed(String)
}

/// A provider's mark: one of the app's brush loops with a letter inside. The
/// loop is the existing artwork, turned and mirrored by the entry's own seed.
struct AIProviderMark: View {
    let entryID: String
    var size: CGFloat = 34

    private var monogram: String {
        switch AIProvider.kind(of: entryID) {
        case .claude: return "C"
        case .openAI: return "G"
        case .deepSeek: return "D"
        case .qwen: return "通"
        case .doubao: return "豆"
        case .kimi: return "K"
        case .zhipu: return "智"
        case .minimax: return "M"
        case .gemini: return "Ge"
        case .custom: return "自"
        }
    }

    var body: some View {
        let seed = InkVariant.seed(for: "ai.provider." + entryID)
        let variant = InkVariant(seed: seed)
        let artworks = ["InkIconLoop", "InkIconCrescent", "InkIconScholarSquare"]
        return ZStack {
            Image(artworks[InkVariant.index(for: seed, count: artworks.count)])
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(Color.planPrimary.opacity(0.70 * variant.opacityScale))
                .inkVariant(variant)
            Text(monogram)
                .font(.system(size: size * (monogram.count > 1 ? 0.30 : 0.38), weight: .semibold))
                .foregroundStyle(Color.planPrimary)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A thin 朱红 label, like a small collector's stamp beside a title.
struct InkTag: View {
    let text: String
    var tint: Color = .planVermilion

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(tint.opacity(0.72), lineWidth: 0.8)
            }
    }
}

private struct AIProviderRowLabel: View {
    @ObservedObject var ai: AISettingsStore
    let entryID: String

    var body: some View {
        HStack(spacing: 12) {
            AIProviderMark(entryID: entryID)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(ai.settings.displayName(for: entryID))
                        .font(.body.weight(.medium))
                    if entryID == ai.settings.activeID { InkTag(text: "正在用") }
                }
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary.opacity(0.88))
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
    }

    private var detail: String {
        let count = ai.settings.savedModels(for: entryID).count
        return ai.keyedIDs.contains(entryID) ? "\(count) 个模型" : "\(count) 个模型 · 未填密钥"
    }
}

private struct AIValueRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title).foregroundStyle(Color.primary)
            Spacer(minLength: 12)
            Text(value)
                .foregroundStyle(Color.planSecondary)
                .lineLimit(1)
                .truncationMode(.middle)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.planSecondary.opacity(0.5))
        }
        .contentShape(Rectangle())
    }
}

private struct AIKeyField: View {
    @Binding var key: String
    @State private var isVisible = false

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if isVisible {
                    TextField("API Key", text: $key)
                } else {
                    SecureField("API Key", text: $key)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.body.monospaced())
            .accessibilityIdentifier("ai.key")

            Button {
                isVisible.toggle()
            } label: {
                Image(systemName: isVisible ? "eye.slash" : "eye")
                    .foregroundStyle(Color.planSecondary)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isVisible ? "隐藏密钥" : "显示密钥")
        }
    }
}

private struct AICheckRow: View {
    let state: AICheckState
    let isDisabled: Bool
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: action) {
                HStack {
                    Text("测试连接")
                    Spacer()
                    if state == .running { InkDraftWaiting(text: "正在连接") }
                }
            }
            .disabled(isDisabled || state == .running)

            switch state {
            case .passed(let seconds):
                Label(String(format: "连接正常，用时 %.1f 秒。", seconds), systemImage: "checkmark.circle")
                    .font(.footnote)
                    .foregroundStyle(Color.planPrimary)
            case .failed(let message):
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Color.planVermilion)
                    .fixedSize(horizontal: false, vertical: true)
            case .idle, .running:
                EmptyView()
            }
        }
    }
}

private extension View {
    func aiSettingsPage(_ title: String) -> some View {
        inkFormStyle()
            .background { InkWashBackground() }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .tint(Color.planPrimary)
    }
}

// MARK: - AI 模型

struct AIModelSettingsView: View {
    /// Where 今天的整理 takes its writes back; the row is left out without it.
    var store: PlanStore? = nil
    @ObservedObject private var ai = AISettingsStore.shared
    @ObservedObject private var writes = AIWriteLog.shared
    @State private var showsSwitcher = false
    @State private var showsAddProvider = false
    @State private var showsHelp = false
    @State private var notice: String?
    @State private var qaProvider: String?

    private var activeHasKey: Bool { ai.keyedIDs.contains(ai.settings.activeID) }

    var body: some View {
        Form {
            Section {
                Toggle("使用 AI 整理", isOn: Binding(
                    get: { ai.settings.isEnabled },
                    set: { value in
                        notice = ai.tryChange { $0.isEnabled = value }
                        AIWeeklyLetterNotifier.reschedule()
                    }
                ))
                .accessibilityIdentifier("ai.enabled")
            } footer: {
                Text(ai.settings.isEnabled
                     ? "开启后，“拆成步骤”“起草复盘”这些操作会把相关的计划或备忘文字发给下面选的模型，结果先以草稿出现，确认后才写入。"
                     : "关闭时墨记不联网，界面和原来一样。")
            }

            Section {
                Toggle("识别首页写下的计划", isOn: Binding(
                    get: { ai.settings.readsQuickEntry },
                    set: { value in notice = ai.tryChange { $0.readsQuickEntry = value } }
                ))
                .accessibilityIdentifier("ai.readsQuickEntry")
                Toggle("今日提要", isOn: Binding(
                    get: { ai.settings.dailyBrief },
                    set: { value in notice = ai.tryChange { $0.dailyBrief = value } }
                ))
                .accessibilityIdentifier("ai.dailyBrief")
                Toggle("晚间收尾", isOn: Binding(
                    get: { ai.settings.eveningWrap },
                    set: { value in notice = ai.tryChange { $0.eveningWrap = value } }
                ))
                .accessibilityIdentifier("ai.eveningWrap")
                Toggle("顺延旁批", isOn: Binding(
                    get: { ai.settings.carryOverNotes },
                    set: { value in notice = ai.tryChange { $0.carryOverNotes = value } }
                ))
                .accessibilityIdentifier("ai.carryOverNotes")
                Toggle("本周来信", isOn: Binding(
                    get: { ai.settings.weeklyLetter },
                    set: { value in
                        notice = ai.tryChange { $0.weeklyLetter = value }
                        AIWeeklyLetterNotifier.reschedule()
                    }
                ))
                .accessibilityIdentifier("ai.weeklyLetter")
                Toggle("没开启时也显示入口", isOn: Binding(
                    get: { ai.settings.showsEntriesWhenOff },
                    set: { value in notice = ai.tryChange { $0.showsEntriesWhenOff = value } }
                ))
                .accessibilityIdentifier("ai.showsEntriesWhenOff")
            } header: {
                Text("在哪里出现")
            } footer: {
                Text("日期、钟点和时长在本机识别。开着今日提要或晚间收尾时，当天第一次显示会把今天计划的标题、时间和最近的倒数日发给模型；本周来信每周把这一周的计划记录发一次。没开启 AI 整理时，今日提要、晚间收尾、本周来信和顺延旁批不出现；其余入口点了会先介绍怎么开启。")
            }

            if let store {
                Section {
                    NavigationLink {
                        AIWritesView(store: store)
                    } label: {
                        HStack {
                            Text("今天的整理")
                            Spacer()
                            Text(writes.activeCount(on: AIClock.now()) > 0
                                 ? "\(writes.activeCount(on: AIClock.now())) 处" : "没有")
                                .foregroundStyle(Color.planSecondary)
                        }
                    }
                    .accessibilityIdentifier("ai.writes")
                }
            }

            Section {
                HStack(spacing: 12) {
                    AIProviderMark(entryID: ai.settings.activeID, size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(ai.settings.displayName(for: ai.settings.activeID))
                            .font(.body.weight(.semibold))
                        Text(ai.settings.configuration.model.isEmpty ? "还没有选模型" : ai.settings.configuration.model)
                            .font(.footnote.monospaced())
                            .foregroundStyle(Color.planSecondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: 0)
                    if ai.isReady { InkSealMark(character: "用", size: 26, style: .round, seed: 12) }
                }
                .padding(.vertical, 4)

                Button("换模型") { showsSwitcher = true }
                    .accessibilityIdentifier("ai.switch")
            } header: {
                Text("墨记正在用")
            } footer: {
                if !activeHasKey {
                    Text("还没有填 API Key。点下面的供应商填写，或添加一个供应商。")
                        .foregroundStyle(Color.planVermilion)
                }
            }

            Section("供应商") {
                ForEach(ai.listedProviderIDs, id: \.self) { id in
                    NavigationLink {
                        AIProviderDetailView(entryID: id)
                    } label: {
                        AIProviderRowLabel(ai: ai, entryID: id)
                    }
                }
                Button {
                    showsAddProvider = true
                } label: {
                    Label("添加供应商", systemImage: "plus")
                }
                .accessibilityIdentifier("ai.addProvider")
            }

            if let notice {
                Section {
                    Text(notice)
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                }
            }
        }
        .aiSettingsPage("AI 模型")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showsHelp = true } label: { Image(systemName: "questionmark.circle") }
                    .accessibilityLabel("用在哪里")
            }
        }
        .sheet(isPresented: $showsSwitcher) {
            AIModelSwitcherView { notice = $0 }
        }
        .sheet(isPresented: $showsAddProvider) {
            AIAddProviderView { notice = $0 }
        }
        .sheet(isPresented: $showsHelp) { AIHelpView() }
        .navigationDestination(item: $qaProvider) { AIProviderDetailView(entryID: $0) }
        .onAppear {
#if DEBUG
            switch ProcessInfo.processInfo.environment["MOJI_QA_AI_PAGE"] {
            case "switcher": showsSwitcher = true
            case "add", "add-custom", "add-preset": showsAddProvider = true
            case "help": showsHelp = true
            case "provider", "model": if qaProvider == nil { qaProvider = ai.settings.activeID }
            default: break
            }
#endif
        }
    }
}

// MARK: - 换模型

private struct AIModelSwitcherView: View {
    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    var onChange: (String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                ForEach(ai.listedProviderIDs, id: \.self) { id in
                    let hasKey = ai.keyedIDs.contains(id)
                    Section {
                        ForEach(ai.settings.savedModels(for: id)) { model in
                            Button {
                                choose(id, model.id)
                            } label: {
                                HStack {
                                    AIModelLabel(provider: AIProvider.kind(of: id), modelID: model.id)
                                    Spacer(minLength: 8)
                                    if ai.settings.isInUse(model: model.id, of: id) {
                                        Image(systemName: "checkmark")
                                            .font(.body.weight(.semibold))
                                            .foregroundStyle(Color.planVermilion)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(!hasKey)
                            .opacity(hasKey ? 1 : 0.45)
                        }
                    } header: {
                        Text(ai.settings.displayName(for: id)).textCase(nil)
                    } footer: {
                        if !hasKey { Text("还没有填密钥，填好后才能选用。") }
                    }
                }
            }
            .aiSettingsPage("换模型")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.fontWeight(.semibold)
                }
            }
        }
    }

    private func choose(_ entryID: String, _ model: String) {
        if let message = ai.tryChange({ $0.activate(entryID, model: model) }) {
            onChange(message)
        } else {
            onChange("已换成 \(ai.settings.displayName(for: entryID)) 的 \(model)。")
        }
        dismiss()
    }
}

private struct AIModelLabel: View {
    let provider: AIProvider
    let modelID: String

    var body: some View {
        let preset = provider.models.first { $0.id == modelID }
        VStack(alignment: .leading, spacing: 2) {
            Text(preset?.title ?? modelID)
                .foregroundStyle(Color.primary)
            Text(preset.map { "\($0.detail) · \(modelID)" } ?? "自己添加的模型")
                .font(.caption)
                .foregroundStyle(Color.planSecondary.opacity(0.88))
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }
}

// MARK: - 添加供应商

private struct AIAddProviderView: View {
    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    var onAdded: (String) -> Void
    @State private var isCustom = false
    @State private var qaPreset: AIProvider?

    var body: some View {
        NavigationStack {
            Group {
                if isCustom {
                    AIProviderSetupForm(provider: .custom, onAdded: finish) { modePicker }
                } else {
                    Form {
                        modePicker
                        Section {
                            ForEach(AIProvider.presets) { provider in
                                let added = ai.keyedIDs.contains(provider.rawValue)
                                NavigationLink {
                                    AIProviderSetupForm(provider: provider, onAdded: finish) { EmptyView() }
                                } label: {
                                    HStack(spacing: 12) {
                                        AIProviderMark(entryID: provider.rawValue)
                                        VStack(alignment: .leading, spacing: 3) {
                                            HStack(spacing: 6) {
                                                Text(provider.title).font(.body.weight(.medium))
                                                if added {
                                                    InkTag(text: "已添加", tint: .planSecondary)
                                                } else if provider == .deepSeek || provider == .claude {
                                                    InkTag(text: "推荐")
                                                }
                                            }
                                            Text(provider.subtitle)
                                                .font(.caption)
                                                .foregroundStyle(Color.planSecondary.opacity(0.88))
                                        }
                                    }
                                    .padding(.vertical, 3)
                                    .opacity(added ? 0.5 : 1)
                                }
                                .disabled(added)
                            }
                        } footer: {
                            Text("密钥在各家的控制台申请，费用由供应商向你收取。墨记的整理都是短文字，用量很小。")
                        }
                    }
                    .aiSettingsPage("添加供应商")
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .navigationDestination(item: $qaPreset) { provider in
                AIProviderSetupForm(provider: provider, onAdded: finish) { EmptyView() }
            }
        }
        .onAppear {
#if DEBUG
            switch ProcessInfo.processInfo.environment["MOJI_QA_AI_PAGE"] {
            case "add-custom": isCustom = true
            case "add-preset": if qaPreset == nil { qaPreset = .kimi }
            default: break
            }
#endif
        }
    }

    private var modePicker: some View {
        Section {
            Picker("添加方式", selection: $isCustom) {
                Text("预设").tag(false)
                Text("自定义").tag(true)
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
    }

    private func finish(_ message: String) {
        onAdded(message)
        dismiss()
    }
}

private struct AIProviderSetupForm<Header: View>: View {
    let provider: AIProvider
    var onAdded: (String) -> Void
    @ViewBuilder var header: () -> Header

    @ObservedObject private var ai = AISettingsStore.shared
    @State private var name = ""
    @State private var key = ""
    @State private var baseURL = ""
    @State private var style = APIStyle.chatCompletions
    @State private var chosen: [String] = []
    @State private var customModel = ""
    @State private var showsAdvanced = false
    @State private var check = AICheckState.idle
    @State private var failure: String?
    @State private var fetched: [String] = []
    @State private var isFetching = false
    @State private var showsFetched = false
    @State private var didPrepare = false

    private var isCustom: Bool { provider == .custom }

    private var modelIDs: [String] {
        if isCustom {
            let id = customModel.trimmingCharacters(in: .whitespacesAndNewlines)
            return id.isEmpty ? [] : [id]
        }
        return provider.models.map(\.id).filter { chosen.contains($0) }
    }

    private var configuration: ProviderConfiguration {
        var value = ProviderConfiguration(provider: provider)
        value.baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        value.style = style
        value.models = modelIDs.map { SavedModel(id: $0, style: style) }
        value.model = modelIDs.first ?? ""
        if isCustom {
            let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
            value.name = clean.isEmpty ? nil : clean
        }
        return value
    }

    private var cleanKey: String { key.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canAdd: Bool { !cleanKey.isEmpty && !modelIDs.isEmpty && !configuration.baseURL.isEmpty }

    var body: some View {
        Form {
            header()

            if isCustom {
                Section {
                    TextField("名称，例如 我的中转", text: $name)
                    TextField("https://…/v1", text: $baseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    Picker("接口格式", selection: $style) {
                        ForEach(APIStyle.allCases) { Text($0.displayName).tag($0) }
                    }
                } header: {
                    Text("服务")
                } footer: {
                    Text(provider.setupNote)
                }
            } else {
                Section {
                    HStack(spacing: 12) {
                        AIProviderMark(entryID: provider.rawValue, size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(provider.title).font(.body.weight(.semibold))
                            Text(provider.subtitle)
                                .font(.footnote)
                                .foregroundStyle(Color.planSecondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Section {
                AIKeyField(key: $key)
                if let url = provider.keyURL {
                    Link(destination: url) {
                        Label("去控制台申请密钥", systemImage: "arrow.up.right.square")
                    }
                }
            } header: {
                Text("API Key").textCase(nil)
            } footer: {
                Text("密钥只保存在本机钥匙串中，不进备份文件。")
            }

            if isCustom {
                Section {
                    TextField("模型 ID", text: $customModel)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                    Button {
                        Task { await fetchModels() }
                    } label: {
                        HStack {
                            Text("获取可用模型")
                            Spacer()
                            if isFetching { InkDraftWaiting(text: "正在获取") }
                        }
                    }
                    .disabled(cleanKey.isEmpty || configuration.baseURL.isEmpty || isFetching)
                } header: {
                    Text("模型")
                }
            } else {
                Section {
                    ForEach(provider.models) { model in
                        Button {
                            if let index = chosen.firstIndex(of: model.id) {
                                chosen.remove(at: index)
                            } else {
                                chosen.append(model.id)
                            }
                            check = .idle
                        } label: {
                            HStack {
                                AIModelLabel(provider: provider, modelID: model.id)
                                Spacer(minLength: 8)
                                Image(systemName: chosen.contains(model.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(chosen.contains(model.id) ? Color.planPrimary : Color.planSecondary.opacity(0.5))
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("模型")
                } footer: {
                    Text("可以多选，之后在“换模型”里切换。排在最前的一个先用上。")
                }

                Section {
                    DisclosureGroup("高级", isExpanded: $showsAdvanced) {
                        TextField("API 地址", text: $baseURL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .font(.footnote.monospaced())
                        Picker("接口格式", selection: $style) {
                            ForEach(APIStyle.allCases) { Text($0.displayName).tag($0) }
                        }
                    }
                } footer: {
                    if showsAdvanced { Text(provider.setupNote) }
                }
            }

            Section {
                AICheckRow(state: check, isDisabled: !canAdd) { Task { await runCheck() } }
                if !isCustom {
                    Link(destination: provider.documentationURL) {
                        Label("官方文档", systemImage: "book")
                    }
                }
            } footer: {
                if let failure {
                    Text(failure).foregroundStyle(Color.planVermilion)
                }
            }
        }
        .aiSettingsPage(isCustom ? "添加供应商" : provider.title)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("添加") { add() }
                    .fontWeight(.semibold)
                    .disabled(!canAdd)
                    .accessibilityIdentifier("ai.add.confirm")
            }
        }
        .sheet(isPresented: $showsFetched) {
            AIFetchedModelPicker(models: fetched) { customModel = $0 }
        }
        .onAppear {
            guard !didPrepare else { return }
            didPrepare = true
            baseURL = provider.defaultURL
            style = provider.defaultStyle
            chosen = provider.models.first.map { [$0.id] } ?? []
        }
    }

    private func runCheck() async {
        check = .running
        do {
            let seconds = try await AIConnectionCheck.run(entryID: provider.rawValue, configuration: configuration, apiKey: cleanKey)
            check = .passed(seconds)
        } catch {
            check = .failed(error.draftMessage)
        }
    }

    private func fetchModels() async {
        isFetching = true
        failure = nil
        do {
            fetched = try await AIClient().models(configuration: configuration, apiKey: cleanKey)
            showsFetched = true
        } catch {
            failure = error.draftMessage
        }
        isFetching = false
    }

    private func add() {
        do {
            let result = try ai.addProvider(provider, configuration: configuration, key: cleanKey)
            let title = ai.settings.displayName(for: result.id)
            onAdded(result.activated ? "已添加 \(title)，现在用它整理。" : "已添加 \(title)。要用它，点“换模型”。")
        } catch {
            failure = error.draftMessage
        }
    }
}

private struct AIFetchedModelPicker: View {
    let models: [String]
    var onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var shown: [String] {
        let clean = query.trimmingCharacters(in: .whitespaces).lowercased()
        return clean.isEmpty ? models : models.filter { $0.lowercased().contains(clean) }
    }

    var body: some View {
        NavigationStack {
            List(shown, id: \.self) { model in
                Button {
                    onPick(model)
                    dismiss()
                } label: {
                    Text(model)
                        .font(.body.monospaced())
                        .foregroundStyle(Color.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .searchable(text: $query, prompt: "搜索模型 ID")
            .aiSettingsPage("可用模型")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            }
        }
    }
}

// MARK: - 供应商

struct AIProviderDetailView: View {
    let entryID: String

    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var showsCredentials = false
    @State private var showsAddModel = false
    @State private var confirmsRemoval = false
    @State private var failure: String?
    @State private var qaModel: String?

    private var kind: AIProvider { AIProvider.kind(of: entryID) }
    private var configuration: ProviderConfiguration { ai.settings.configuration(for: entryID) }
    private var hasKey: Bool { ai.keyedIDs.contains(entryID) }
    private var isActive: Bool { entryID == ai.settings.activeID }

    private var host: String {
        URLComponents(string: configuration.baseURL)?.host ?? configuration.baseURL
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    AIProviderMark(entryID: entryID, size: 44)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(ai.settings.displayName(for: entryID)).font(.title3.weight(.semibold))
                            if isActive { InkTag(text: "正在用") }
                        }
                        Text(kind == .custom ? configuration.style.displayName : kind.subtitle)
                            .font(.footnote)
                            .foregroundStyle(Color.planSecondary)
                    }
                }
                .padding(.vertical, 4)
            }

            Section {
                Button { showsCredentials = true } label: {
                    AIValueRow(title: "API Key", value: hasKey ? "已填写" : "未填写")
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ai.provider.key")
                Button { showsCredentials = true } label: {
                    AIValueRow(title: "地址", value: host)
                }
                .buttonStyle(.plain)
            } header: {
                Text("密钥与地址")
            } footer: {
                Text("密钥只保存在本机钥匙串中。")
            }

            Section("模型") {
                ForEach(ai.settings.savedModels(for: entryID)) { model in
                    NavigationLink {
                        AIModelDetailView(entryID: entryID, modelID: model.id)
                    } label: {
                        HStack {
                            AIModelLabel(provider: kind, modelID: model.id)
                            Spacer(minLength: 8)
                            if ai.settings.isInUse(model: model.id, of: entryID) { InkTag(text: "正在用") }
                        }
                    }
                }
                Button { showsAddModel = true } label: { Label("添加模型", systemImage: "plus") }
            }

            if kind != .custom {
                Section {
                    Link(destination: kind.documentationURL) { Label("官方文档", systemImage: "book") }
                }
            }

            Section {
                Button("删除这个供应商", role: .destructive) { confirmsRemoval = true }
                    .disabled(isActive)
            } footer: {
                if isActive {
                    Text("正在用的供应商不能删除。先在“换模型”里换到别家。")
                } else if let failure {
                    Text(failure).foregroundStyle(Color.planVermilion)
                }
            }
        }
        .aiSettingsPage(ai.settings.displayName(for: entryID))
        .sheet(isPresented: $showsCredentials) { AIProviderCredentialsEditor(entryID: entryID) }
        .sheet(isPresented: $showsAddModel) { AIAddModelView(entryID: entryID) }
        .navigationDestination(item: $qaModel) { AIModelDetailView(entryID: entryID, modelID: $0) }
        .confirmationDialog("删除这个供应商？", isPresented: $confirmsRemoval, titleVisibility: .visible) {
            Button("删除，并清除它的密钥", role: .destructive) {
                do {
                    try ai.removeProvider(entryID)
                    dismiss()
                } catch {
                    failure = error.draftMessage
                }
            }
            Button("取消", role: .cancel) {}
        }
        .onAppear {
#if DEBUG
            if ProcessInfo.processInfo.environment["MOJI_QA_AI_PAGE"] == "model", qaModel == nil {
                qaModel = configuration.model
            }
#endif
        }
    }
}

private struct AIProviderCredentialsEditor: View {
    let entryID: String

    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var key = ""
    @State private var baseURL = ""
    @State private var failure: String?
    @State private var didPrepare = false

    private var kind: AIProvider { AIProvider.kind(of: entryID) }

    var body: some View {
        NavigationStack {
            Form {
                if kind == .custom {
                    Section("名称") { TextField("名称", text: $name) }
                }
                Section {
                    AIKeyField(key: $key)
                    if let url = kind.keyURL {
                        Link(destination: url) { Label("去控制台申请密钥", systemImage: "arrow.up.right.square") }
                    }
                } header: {
                    Text("API Key").textCase(nil)
                } footer: {
                    Text("留空并保存会清除已存的密钥。")
                }
                Section {
                    TextField("API 地址", text: $baseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(.footnote.monospaced())
                    if kind != .custom, baseURL != kind.defaultURL {
                        Button("恢复官方地址") { baseURL = kind.defaultURL }
                    }
                } header: {
                    Text("地址")
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(kind.setupNote)
                        if let failure { Text(failure).foregroundStyle(Color.planVermilion) }
                    }
                }
            }
            .aiSettingsPage("密钥与地址")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }.fontWeight(.semibold)
                }
            }
        }
        .onAppear {
            guard !didPrepare else { return }
            didPrepare = true
            let configuration = ai.settings.configuration(for: entryID)
            name = configuration.name ?? ""
            baseURL = configuration.baseURL
            key = ai.apiKey(for: entryID)
        }
    }

    private func save() {
        let cleanKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let message = ai.tryChange(keys: [entryID: cleanKey]) { settings in
            settings.updateConfiguration(for: entryID) { configuration in
                configuration.baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
                if kind == .custom { configuration.name = cleanName.isEmpty ? nil : cleanName }
            }
            if settings.providerList == nil { settings.providerList = settings.listedProviderIDs(keyed: ai.keyedIDs) }
        }
        if let message { failure = message } else { dismiss() }
    }
}

private struct AIAddModelView: View {
    let entryID: String

    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var modelID = ""
    @State private var fetched: [String] = []
    @State private var isFetching = false
    @State private var showsFetched = false
    @State private var failure: String?

    private var kind: AIProvider { AIProvider.kind(of: entryID) }
    private var cleanID: String { modelID.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var suggestions: [ModelPreset] {
        let kept = Set(ai.settings.savedModels(for: entryID).map(\.id))
        return kind.models.filter { !kept.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("模型 ID", text: $modelID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                    Button {
                        Task { await fetchModels() }
                    } label: {
                        HStack {
                            Text("获取可用模型")
                            Spacer()
                            if isFetching { InkDraftWaiting(text: "正在获取") }
                        }
                    }
                    .disabled(!ai.keyedIDs.contains(entryID) || isFetching)
                } header: {
                    Text("模型")
                } footer: {
                    if let failure {
                        Text(failure).foregroundStyle(Color.planVermilion)
                    } else {
                        Text("模型 ID 以供应商文档里写的为准。")
                    }
                }

                if !suggestions.isEmpty {
                    Section("常用") {
                        ForEach(suggestions) { preset in
                            Button {
                                modelID = preset.id
                            } label: {
                                AIModelLabel(provider: kind, modelID: preset.id)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .aiSettingsPage("添加模型")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") { add() }
                        .fontWeight(.semibold)
                        .disabled(cleanID.isEmpty)
                }
            }
            .sheet(isPresented: $showsFetched) {
                AIFetchedModelPicker(models: fetched) { modelID = $0 }
            }
        }
    }

    private func fetchModels() async {
        isFetching = true
        failure = nil
        do {
            fetched = try await AIClient().models(configuration: ai.settings.configuration(for: entryID),
                                                  apiKey: ai.apiKey(for: entryID))
            showsFetched = true
        } catch {
            failure = error.draftMessage
        }
        isFetching = false
    }

    private func add() {
        let style = ai.settings.configuration(for: entryID).style
        let message = ai.tryChange { settings in
            settings.updateConfiguration(for: entryID) { $0.upsert(SavedModel(id: cleanID, style: style), provider: kind) }
        }
        if let message { failure = message } else { dismiss() }
    }
}

// MARK: - 模型

struct AIModelDetailView: View {
    let entryID: String
    let modelID: String

    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var check = AICheckState.idle
    @State private var failure: String?
    @State private var confirmsRemoval = false

    private var kind: AIProvider { AIProvider.kind(of: entryID) }
    private var models: [SavedModel] { ai.settings.savedModels(for: entryID) }
    private var model: SavedModel {
        models.first { $0.id == modelID } ?? SavedModel(id: modelID, style: ai.settings.configuration(for: entryID).style)
    }
    private var isInUse: Bool { ai.settings.isInUse(model: modelID, of: entryID) }
    private var hasKey: Bool { ai.keyedIDs.contains(entryID) }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(kind.models.first { $0.id == modelID }?.title ?? modelID)
                            .font(.title3.weight(.semibold))
                        if isInUse { InkTag(text: "正在用") }
                    }
                    Text(modelID)
                        .font(.footnote.monospaced())
                        .foregroundStyle(Color.planSecondary)
                        .textSelection(.enabled)
                }
                .padding(.vertical, 4)
            }

            Section {
                Picker("接口格式", selection: Binding(
                    get: { model.style },
                    set: { value in update { $0.style = value } }
                )) {
                    ForEach(APIStyle.allCases) { Text($0.displayName).tag($0) }
                }
            } footer: {
                Text("不确定就保持默认。接口格式选错时，测试连接会提示返回格式不兼容。")
            }

            Section {
                Picker("思考强度", selection: Binding(
                    get: { model.reasoning },
                    set: { value in update { $0.reasoning = value } }
                )) {
                    Text("自动").tag(ReasoningEffort?.none)
                    ForEach(kind.reasoningOptions) { Text($0.title).tag(ReasoningEffort?.some($0)) }
                }
            } header: {
                Text("高级")
            } footer: {
                Text(kind.reasoningNote)
            }

            Section {
                AICheckRow(state: check, isDisabled: !hasKey) { Task { await runCheck() } }
            } footer: {
                if !hasKey { Text("先给这个供应商填好密钥。") }
            }

            Section {
                Button("设为正在用") {
                    failure = ai.tryChange { $0.activate(entryID, model: modelID) }
                }
                .disabled(isInUse || !hasKey)
                Button("删除这个模型", role: .destructive) { confirmsRemoval = true }
                    .disabled(models.count <= 1)
            } footer: {
                if let failure {
                    Text(failure).foregroundStyle(Color.planVermilion)
                } else if models.count <= 1 {
                    Text("供应商下至少保留一个模型。")
                }
            }
        }
        .aiSettingsPage("模型")
        .confirmationDialog("删除这个模型？", isPresented: $confirmsRemoval, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let message = ai.tryChange({ settings in
                    settings.updateConfiguration(for: entryID) { $0.removeModel(modelID, provider: kind) }
                }) {
                    failure = message
                } else {
                    dismiss()
                }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private func update(_ change: (inout SavedModel) -> Void) {
        var value = model
        change(&value)
        check = .idle
        failure = ai.tryChange { settings in
            settings.updateConfiguration(for: entryID) { $0.upsert(value, provider: kind) }
        }
    }

    private func runCheck() async {
        var configuration = ai.settings.configuration(for: entryID)
        configuration.model = model.id
        configuration.style = model.style
        configuration.reasoning = model.reasoning
        check = .running
        do {
            let seconds = try await AIConnectionCheck.run(entryID: entryID, configuration: configuration,
                                                          apiKey: ai.apiKey(for: entryID))
            check = .passed(seconds)
        } catch {
            check = .failed(error.draftMessage)
        }
    }
}

// MARK: - 用在哪里

private struct AIHelpView: View {
    @Environment(\.dismiss) private var dismiss

    private let places: [(String, String, String)] = [
        ("square.and.pencil", "首页写下计划", "带有“明天下午”“30 分钟”这类线索时，在那一行下面给出建议，点了才应用。"),
        ("list.bullet.indent", "计划 › 展开 › 拆成步骤", "把一件大的计划拆成几步；点顺延印也能“拆小一点”。"),
        ("sunrise", "今日提要", "今日计划上方，每天一张：今天几件、离最近的日子还有几天、哪件顺延了，并建议先做哪件。"),
        ("moon.stars", "晚间收尾", "21 点以后在今日计划上方：今天完成几件、专注多久；剩下的每件给一个安排（顺延到明天、明早先做、拆小、放下），照此处理前可以逐条改。"),
        ("text.append", "顺延旁批", "同一条计划顺延满 3 天，行下出一行朱字，点开就是拆小的草稿；点过“不用”就不再提。"),
        ("envelope", "本周来信", "周日 20:00 起在回顾页：一个题签、几句这一周的情况和依据；可以放进复盘、拟下周计划，往期来信都留在本机。"),
        ("doc.text.magnifyingglass", "回顾 › 周复盘", "打开时先按这一周的记录放一份草稿进两栏，并注明依据的计划，可以直接改或清空；复盘存好后，会提议按“下周关注”拟下周草稿。"),
        ("checklist", "备忘 › 备忘操作 › 整理成清单", "把正文里要做的事整理成清单；转为计划时预填日期和类型。"),
        ("timer", "时刻 › 新建后 › 准备计划", "为一个重要的日子拟几条提前要做的事。"),
        ("line.3.horizontal.decrease", "计划库 › 搜索框", "写一句“上周没做完的学习计划”，替你设好筛选。"),
        ("text.viewfinder", "首页 ＋ › 从文字或截图添加", "从粘贴的文字或一张截图里找出要做的事。截图的文字在本机识别。"),
        ("signature", "留痕", "墨记写入的计划，小字里带朱色的来源；当天写入的都能在「今天的整理」里逐条撤销。"),
        ("square.and.arrow.up", "分享", "在别的 App 里把文字或截图分享给墨记，打开墨记时先出计划草稿。"),
        ("mic", "说一句添加", "锁屏小组件、控制中心（iOS 18 起）或快捷指令里点一下，打开墨记直接说。")
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(places, id: \.1) { place in
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: place.0)
                                .foregroundStyle(Color.planPrimary)
                                .frame(width: 24)
                                .padding(.top, 2)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(place.1).font(.subheadline.weight(.medium))
                                Text(place.2)
                                    .font(.caption)
                                    .foregroundStyle(Color.planSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }

                Section("会发送什么") {
                    Text("只在你点了上面这些操作时，把那一处用到的文字（计划标题、备忘正文、一周的计划记录）发给你选的供应商。请求从手机直接发出，不经过墨记的服务器。")
                        .font(.footnote)
                    Text("首页识别是例外：开着“识别首页写下的计划”时，带时间线索的新计划会在添加后自动发去识别。可以单独关掉。")
                        .font(.footnote)
                    Text("今日提要也是：开着时，每天第一次打开首页会把今天计划的标题、时间和最近的倒数日发去排先后。可以单独关掉。")
                        .font(.footnote)
                    Text("晚间收尾和今日提要一样，只发今天计划的标题和时间；本周来信每周发一次这一周的计划记录。都可以单独关掉。")
                        .font(.footnote)
                }

                Section("不会做什么") {
                    Text("结果都先以草稿出现，带一枚“稿”字印；你确认后才写入，写入后可以撤销。")
                        .font(.footnote)
                    Text("密钥只在本机钥匙串里，不进备份文件；小组件不联网。")
                        .font(.footnote)
                }
            }
            .aiSettingsPage("用在哪里")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.fontWeight(.semibold)
                }
            }
        }
    }
}
