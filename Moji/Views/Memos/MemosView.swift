import SwiftUI
import UIKit

private struct MemoEditorRoute: Identifiable {
    let id = UUID()
    let memo: MemoItem?
}

struct MemosView: View {
    @ObservedObject var store: PlanStore

    @State private var editorRoute: MemoEditorRoute?
    @State private var searchText = ""
#if DEBUG
    @State private var didRunQAScenario = false
#endif

    private var pinnedMemos: [MemoItem] {
        filteredMemos.filter(\.isPinned).sorted { $0.updatedAt > $1.updatedAt }
    }

    private var recentMemos: [MemoItem] {
        filteredMemos.filter { !$0.isPinned }.sorted { $0.updatedAt > $1.updatedAt }
    }

    private var filteredMemos: [MemoItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return store.memos }
        return store.memos.filter {
            $0.title.localizedCaseInsensitiveContains(query)
                || $0.content.localizedCaseInsensitiveContains(query)
                || $0.checklistItems.contains {
                    $0.text.localizedCaseInsensitiveContains(query)
                }
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                List {
                    if store.memos.isEmpty {
                        emptyState
                            .listRowInsets(
                                EdgeInsets(
                                    top: 80,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 20,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    } else if filteredMemos.isEmpty {
                        searchEmptyState
                            .listRowInsets(
                                EdgeInsets(
                                    top: 80,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 20,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    } else {
                        memoSection(title: "置顶", memos: pinnedMemos)
                        memoSection(title: "最近", memos: recentMemos)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .refreshable { store.reload() }
            }
            .navigationTitle("备忘")
            .searchable(
                text: $searchText,
                placement: .navigationBarDrawer(displayMode: .automatic),
                prompt: "搜索备忘录"
            )
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        editorRoute = MemoEditorRoute(memo: nil)
                    } label: {
                        Image(systemName: "square.and.pencil")
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("新建备忘")
                }
            }
            .sheet(item: $editorRoute) { route in
                MemoEditorView(store: store, memo: route.memo)
                    .id(route.id)
            }
            .onAppear {
                store.reload()
#if DEBUG
                runQAScenarioIfNeeded()
#endif
            }
        }
    }

    @ViewBuilder
    private func memoSection(title: String, memos: [MemoItem]) -> some View {
        if !memos.isEmpty {
            Section {
                ForEach(memos) { memo in
                    memoRow(memo)
                }
            } header: {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(nil)
                    .padding(.horizontal, 2)
            }
        }
    }

    private func memoRow(_ memo: MemoItem) -> some View {
        Button {
            editorRoute = MemoEditorRoute(memo: memo)
        } label: {
            VStack(alignment: .leading, spacing: 9) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if memo.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.planVermilion)
                            .accessibilityHidden(true)
                    }

                    Text(memo.displayTitle)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Text(timestampText(for: memo.updatedAt))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(Color.planSecondary.opacity(0.82))
                        .lineLimit(1)
                }

                if !previewText(for: memo).isEmpty {
                    Text(previewText(for: memo))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                        .lineLimit(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            InkBrushDivider(
                animated: false,
                seed: InkVariant.seed(for: memo.id.uuidString)
            )
            .padding(.leading, 14)
            .opacity(0.30)
        }
        .listRowInsets(
            EdgeInsets(
                top: 1,
                leading: PlanLayout.pageHorizontalPadding,
                bottom: 1,
                trailing: PlanLayout.pageHorizontalPadding
            )
        )
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                store.toggleMemoPin(id: memo.id)
            } label: {
                Label(
                    memo.isPinned ? "取消置顶" : "置顶",
                    systemImage: memo.isPinned ? "pin.slash" : "pin"
                )
            }
            .tint(Color.planSecondary)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                store.deleteMemo(id: memo.id)
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
        .contextMenu {
            Button {
                store.toggleMemoPin(id: memo.id)
            } label: {
                Label(
                    memo.isPinned ? "取消置顶" : "置顶",
                    systemImage: memo.isPinned ? "pin.slash" : "pin"
                )
            }
            Button(role: .destructive) {
                store.deleteMemo(id: memo.id)
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            InkBrushMedallion(symbol: "note.text", tint: .planPrimary, size: 48)
            Text("纸上留白")
                .font(.title3.weight(.medium))
            Text("只记下想法和事情，不设完成、不计时。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button {
                editorRoute = MemoEditorRoute(memo: nil)
            } label: {
                Label("写一则备忘", systemImage: "square.and.pencil")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(
                        Color.planPrimary.opacity(0.07),
                        in: Capsule()
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
    }

    private var searchEmptyState: some View {
        VStack(spacing: 11) {
            InkBrushMedallion(symbol: "magnifyingglass", tint: .planPrimary, size: 44)
            Text("没有找到备忘")
                .font(.headline)
            Text("没有找到“\(searchText)”\n试试标题或正文里的其他关键词。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button {
                searchText = ""
            } label: {
                Text("清除搜索")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(
                        Color.planPrimary.opacity(0.07),
                        in: Capsule()
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
    }

    private func previewText(for memo: MemoItem) -> String {
        if memo.mode == .checklist {
            let nonemptyItems = memo.checklistItems.filter {
                !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            let visibleItems = memo.title
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty ? Array(nonemptyItems.dropFirst()) : nonemptyItems
            let previewItems = Array(visibleItems.prefix(3))
            var lines = previewItems.map {
                "\($0.isCompleted ? "◉" : "○") \($0.text)"
            }
            if visibleItems.count > previewItems.count {
                lines.append("另有 \(visibleItems.count - previewItems.count) 项")
            }
            return lines.joined(separator: "\n")
        }

        let content = memo.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard memo.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return content
        }
        let lines = content.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count > 1 else { return "" }
        return lines.dropFirst().joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func timestampText(for date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if Calendar.current.isDateInYesterday(date) {
            return "昨天"
        }
        return date.formatted(.dateTime.month().day())
    }

#if DEBUG
    private func runQAScenarioIfNeeded() {
        guard
            !didRunQAScenario,
            ["memo-list", "memo-editor", "memo-checklist"].contains(
                ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
            )
        else { return }

        didRunQAScenario = true
        let now = Date()
        let fixtures = [
            MemoItem(
                title: "测试版反馈",
                content: "首页的留白很舒服。\n下次再看看深色模式下的印章对比度。",
                isPinned: true,
                createdAt: now.addingTimeInterval(-7_200),
                updatedAt: now.addingTimeInterval(-1_800)
            ),
            MemoItem(
                title: "下周想做的事",
                content: "收集几张新的宣纸纹理，记录灵感，暂时不安排日期。",
                createdAt: now.addingTimeInterval(-86_400),
                updatedAt: now.addingTimeInterval(-3_600)
            ),
            MemoItem(
                content: "书单\n《长安的荔枝》\n《苏东坡新传》",
                createdAt: now.addingTimeInterval(-172_800),
                updatedAt: now.addingTimeInterval(-172_800)
            ),
            MemoItem(
                title: "周末出行清单",
                mode: .checklist,
                checklistItems: [
                    MemoChecklistItem(text: "确认车票", isCompleted: true),
                    MemoChecklistItem(text: "带上充电器"),
                    MemoChecklistItem(text: "预留一本路上看的书")
                ],
                createdAt: now.addingTimeInterval(-10_800),
                updatedAt: now.addingTimeInterval(-900)
            )
        ]
        for fixture in fixtures where !store.memos.contains(where: { $0.title == fixture.title }) {
            store.saveMemo(fixture)
        }

        let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"]
        if scenario == "memo-editor" || scenario == "memo-checklist" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                editorRoute = MemoEditorRoute(memo: scenario == "memo-checklist" ? fixtures[3] : fixtures[0])
            }
        }
    }
#endif
}

private struct MemoEditorView: View {
    @ObservedObject var store: PlanStore
    let memo: MemoItem?

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var content: String
    @State private var mode: MemoMode
    @State private var checklistItems: [MemoChecklistItem]
    @State private var isPinned: Bool
    @State private var isTitleFocused = false
    @State private var showsDeleteConfirmation = false
    @State private var focusedChecklistItemID: UUID?
    @State private var checklistCaretRequest: ChecklistCaretRequest?

    init(store: PlanStore, memo: MemoItem? = nil) {
        self.store = store
        self.memo = memo
        _title = State(initialValue: memo?.title ?? "")
        _content = State(initialValue: memo?.content ?? "")
        _mode = State(initialValue: memo?.mode ?? .note)
        _checklistItems = State(initialValue: memo?.checklistItems ?? [])
        _isPinned = State(initialValue: memo?.isPinned ?? false)
    }

    private var cleanTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var cleanContent: String {
        content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSave: Bool {
        if mode == .checklist {
            return !cleanTitle.isEmpty || checklistItems.contains {
                !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
        }
        return !cleanTitle.isEmpty || !cleanContent.isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        IMESafeTextField(
                            placeholder: "标题",
                            text: $title,
                            isFocused: $isTitleFocused,
                            accessibilityIdentifier: "memo.editor.title",
                            textStyle: .title2
                        )
                        .frame(maxWidth: .infinity)
                        .layoutPriority(1)

                        if isPinned {
                            InkSealMark(
                                character: "藏",
                                size: 23,
                                style: .doubleSquare,
                                seed: InkVariant.seed(for: memo?.id.uuidString ?? cleanTitle)
                            )
                            .fixedSize()
                        }
                    }
                    .padding(.horizontal, 14)
                    .frame(height: 58)

                    Group {
                        if mode == .checklist {
                            checklistEditor
                        } else {
                            noteEditor
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .inkPaperSurface(cornerRadius: 16, showsBrushMark: true)
                .padding(.horizontal, PlanLayout.pageHorizontalPadding)
                .padding(.top, 14)
                .padding(.bottom, 10)
            }
            .navigationTitle(memo == nil ? "新建备忘" : "编辑备忘")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Menu {
                        Button {
                            isPinned.toggle()
                        } label: {
                            Label(
                                isPinned ? "取消置顶" : "置顶",
                                systemImage: isPinned ? "pin.slash" : "pin"
                            )
                        }
                        if memo != nil {
                            Button(role: .destructive) {
                                showsDeleteConfirmation = true
                            } label: {
                                Label("删除备忘", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("备忘操作")

                    Button("完成") {
                        IMETextInput.commit(save)
                    }
                    .fontWeight(.semibold)
                    .disabled(!canSave)
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button {
                        switchMemoMode()
                    } label: {
                        Label(
                            mode == .checklist ? "转为正文" : "核对清单",
                            systemImage: mode == .checklist ? "text.alignleft" : "checklist"
                        )
                    }
                    .accessibilityIdentifier("memo.editor.toggleChecklist")

                    if mode == .checklist {
                        Button {
                            addChecklistItem(after: focusedChecklistItemID)
                        } label: {
                            Label("添加项目", systemImage: "plus")
                        }

                        if checklistItems.contains(where: \.isCompleted) {
                            Menu {
                                Button {
                                    clearCompletedChecklistItems()
                                } label: {
                                    Label("清除已完成", systemImage: "checkmark.circle.badge.xmark")
                                }
                            } label: {
                                Label("清单操作", systemImage: "ellipsis")
                            }
                        }
                    }
                }
            }
            .onAppear {
                if mode == .checklist, checklistItems.isEmpty {
                    checklistItems = [MemoChecklistItem()]
                }
                if memo == nil, mode == .note {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        isTitleFocused = true
                    }
                }
            }
            .alert("删除这则备忘？", isPresented: $showsDeleteConfirmation) {
                Button("删除", role: .destructive) {
                    guard let memo else { return }
                    store.deleteMemo(id: memo.id)
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("删除后无法撤销。")
            }
        }
    }

    private var noteEditor: some View {
        ZStack(alignment: .topLeading) {
            if content.isEmpty {
                Text("开始记录……")
                    .font(.body)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 15)
                    .padding(.vertical, 13)
                    .allowsHitTesting(false)
            }

            TextEditor(text: $content)
                .font(.body)
                .lineSpacing(5)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, 8)
                .padding(.bottom, 8)
                .accessibilityIdentifier("memo.editor.content")
        }
    }

    private var checklistEditor: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                ForEach($checklistItems) { $item in
                    HStack(alignment: .top, spacing: 3) {
                        Button {
                            item.isCompleted.toggle()
                        } label: {
                            Image(
                                systemName: item.isCompleted
                                    ? "checkmark.circle.fill"
                                    : "circle"
                            )
                            .font(.title3.weight(.medium))
                            .foregroundStyle(
                                item.isCompleted ? Color.planVermilion : Color.secondary
                            )
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(item.isCompleted ? "取消完成" : "标记完成")

                        ChecklistItemTextView(
                            text: $item.text,
                            itemID: item.id,
                            isCompleted: item.isCompleted,
                            focusedItemID: $focusedChecklistItemID,
                            caretRequest: checklistCaretRequest,
                            onReturn: { selection in
                                handleChecklistReturn(
                                    itemID: item.id,
                                    selection: selection
                                )
                            },
                            onDeleteAtStart: {
                                handleChecklistBackspace(itemID: item.id)
                            }
                        )
                            .accessibilityIdentifier("memo.editor.checklistItem")
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .contextMenu {
                        Button(role: .destructive) {
                            removeChecklistItem(id: item.id)
                        } label: {
                            Label("删除项目", systemImage: "trash")
                        }
                    }
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 14)
        }
        .scrollDismissesKeyboard(.interactively)
        .accessibilityIdentifier("memo.editor.checklist")
    }

    private func switchMemoMode() {
        if mode == .note {
            let parsedItems = content.components(separatedBy: .newlines)
                .compactMap(parsedChecklistItem(from:))
            checklistItems = parsedItems.isEmpty ? [MemoChecklistItem()] : parsedItems
            content = ""
            mode = .checklist
            isTitleFocused = false
            let firstID = checklistItems.first?.id
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                focusedChecklistItemID = firstID
            }
        } else {
            content = cleanedChecklistItems.map {
                "\($0.isCompleted ? "✓" : "•") \($0.text)"
            }.joined(separator: "\n")
            focusedChecklistItemID = nil
            mode = .note
        }
    }

    private func parsedChecklistItem(from rawLine: String) -> MemoChecklistItem? {
        var line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return nil }

        let completedPrefixes = ["- [x] ", "- [X] ", "☑︎ ", "☑ ", "✓ ", "✔ "]
        for prefix in completedPrefixes where line.hasPrefix(prefix) {
            line.removeFirst(prefix.count)
            return MemoChecklistItem(
                text: line.trimmingCharacters(in: .whitespacesAndNewlines),
                isCompleted: true
            )
        }

        let openPrefixes = ["- [ ] ", "☐ ", "○ ", "• "]
        for prefix in openPrefixes where line.hasPrefix(prefix) {
            line.removeFirst(prefix.count)
            break
        }
        return MemoChecklistItem(
            text: line.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    private func addChecklistItem(after itemID: UUID? = nil) {
        if
            let itemID,
            let existing = checklistItems.first(where: { $0.id == itemID }),
            existing.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            requestChecklistFocus(itemID: itemID, utf16Offset: 0)
            return
        }
        if itemID == nil,
           let last = checklistItems.last,
           last.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            requestChecklistFocus(itemID: last.id, utf16Offset: 0)
            return
        }

        let newItem = MemoChecklistItem()
        if
            let itemID,
            let index = checklistItems.firstIndex(where: { $0.id == itemID })
        {
            checklistItems.insert(newItem, at: index + 1)
        } else {
            checklistItems.append(newItem)
        }
        requestChecklistFocus(itemID: newItem.id, utf16Offset: 0)
    }

    private func removeChecklistItem(id: UUID) {
        if let caret = MemoChecklistEditingEngine.removeItem(
            items: &checklistItems,
            itemID: id
        ) {
            requestChecklistFocus(caret)
        }
    }

    private func clearCompletedChecklistItems() {
        checklistItems.removeAll { $0.isCompleted }
        if checklistItems.isEmpty {
            let replacement = MemoChecklistItem()
            checklistItems = [replacement]
            requestChecklistFocus(itemID: replacement.id, utf16Offset: 0)
        } else if
            let focusedChecklistItemID,
            !checklistItems.contains(where: { $0.id == focusedChecklistItemID })
        {
            let first = checklistItems[0]
            requestChecklistFocus(itemID: first.id, utf16Offset: 0)
        }
    }

    private func handleChecklistReturn(itemID: UUID, selection: NSRange) {
        switch MemoChecklistEditingEngine.handleReturn(
            items: &checklistItems,
            itemID: itemID,
            selection: selection
        ) {
        case .focus(let caret):
            requestChecklistFocus(caret)
        case .endEditing:
            focusedChecklistItemID = nil
            checklistCaretRequest = nil
        case .unchanged:
            break
        }
    }

    private func handleChecklistBackspace(itemID: UUID) -> Bool {
        switch MemoChecklistEditingEngine.handleBackspaceAtStart(
            items: &checklistItems,
            itemID: itemID
        ) {
        case .handled(let caret):
            requestChecklistFocus(caret)
            return true
        case .system:
            return false
        }
    }

    private func requestChecklistFocus(_ caret: MemoChecklistCaret) {
        requestChecklistFocus(
            itemID: caret.itemID,
            utf16Offset: caret.utf16Offset
        )
    }

    private func requestChecklistFocus(itemID: UUID, utf16Offset: Int) {
        focusedChecklistItemID = itemID
        checklistCaretRequest = ChecklistCaretRequest(
            itemID: itemID,
            utf16Offset: utf16Offset,
            token: UUID()
        )
    }

    private var cleanedChecklistItems: [MemoChecklistItem] {
        checklistItems.compactMap { item in
            let cleanText = item.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleanText.isEmpty else { return nil }
            var cleaned = item
            cleaned.text = cleanText
            return cleaned
        }
    }

    private func save() {
        guard canSave else { return }
        let now = Date()
        let normalized = normalizedText
        store.saveMemo(
            MemoItem(
                id: memo?.id ?? UUID(),
                title: normalized.title,
                content: normalized.content,
                mode: mode,
                checklistItems: mode == .checklist ? cleanedChecklistItems : [],
                isPinned: isPinned,
                createdAt: memo?.createdAt ?? now,
                updatedAt: now
            )
        )
        dismiss()
    }

    private var normalizedText: (title: String, content: String) {
        if mode == .checklist {
            return (cleanTitle, "")
        }
        guard cleanTitle.isEmpty else {
            return (cleanTitle, cleanContent)
        }
        let lines = cleanContent.components(separatedBy: .newlines)
        let inferredTitle = lines.first?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let remainingContent = lines.dropFirst().joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (inferredTitle, remainingContent)
    }
}

private struct ChecklistCaretRequest: Equatable {
    let itemID: UUID
    let utf16Offset: Int
    let token: UUID
}

private final class ChecklistUIKitTextView: UITextView {
    var deleteAtStart: (() -> Bool)?

    override func deleteBackward() {
        if
            markedTextRange == nil,
            selectedRange.location == 0,
            selectedRange.length == 0,
            deleteAtStart?() == true
        {
            return
        }
        super.deleteBackward()
    }
}

private struct ChecklistItemTextView: UIViewRepresentable {
    @Binding var text: String
    let itemID: UUID
    let isCompleted: Bool
    @Binding var focusedItemID: UUID?
    let caretRequest: ChecklistCaretRequest?
    let onReturn: (NSRange) -> Void
    let onDeleteAtStart: () -> Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> ChecklistUIKitTextView {
        let textView = ChecklistUIKitTextView()
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.font = .preferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = true
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 0, bottom: 8, right: 4)
        textView.textContainer.lineFragmentPadding = 0
        textView.returnKeyType = .next
        textView.isScrollEnabled = false
        textView.attributedText = NSAttributedString(
            string: text,
            attributes: typingAttributes
        )
        context.coordinator.lastCompletionState = isCompleted
        textView.accessibilityLabel = "清单项目"
        textView.deleteAtStart = { [weak coordinator = context.coordinator] in
            coordinator?.parent.onDeleteAtStart() ?? false
        }
        return textView
    }

    func updateUIView(_ textView: ChecklistUIKitTextView, context: Context) {
        context.coordinator.parent = self
        textView.deleteAtStart = { [weak coordinator = context.coordinator] in
            coordinator?.parent.onDeleteAtStart() ?? false
        }
        textView.textColor = isCompleted ? .secondaryLabel : .label
        textView.tintColor = .label
        textView.typingAttributes = typingAttributes

        if textView.markedTextRange == nil,
           textView.text != text || context.coordinator.lastCompletionState != isCompleted
        {
            let selection = textView.selectedRange
            textView.attributedText = NSAttributedString(
                string: text,
                attributes: typingAttributes
            )
            textView.selectedRange = clamped(selection, for: textView.text)
            context.coordinator.lastCompletionState = isCompleted
        }

        if let caretRequest,
           caretRequest.itemID == itemID,
           context.coordinator.appliedCaretToken != caretRequest.token
        {
            context.coordinator.appliedCaretToken = caretRequest.token
            let desired = NSRange(location: caretRequest.utf16Offset, length: 0)
            DispatchQueue.main.async {
                textView.selectedRange = clamped(desired, for: textView.text)
            }
        }

        if focusedItemID == itemID, !textView.isFirstResponder {
            DispatchQueue.main.async {
                guard textView.window != nil else { return }
                textView.becomeFirstResponder()
            }
        } else if focusedItemID != itemID, textView.isFirstResponder {
            textView.resignFirstResponder()
        }
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: ChecklistUIKitTextView,
        context: Context
    ) -> CGSize? {
        guard let width = proposal.width else { return nil }
        let measured = uiView.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        )
        return CGSize(width: width, height: min(max(44, ceil(measured.height)), 124))
    }

    private var typingAttributes: [NSAttributedString.Key: Any] {
        var attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.preferredFont(forTextStyle: .body),
            .foregroundColor: isCompleted ? UIColor.secondaryLabel : UIColor.label
        ]
        if isCompleted {
            attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
            attributes[.strikethroughColor] = UIColor.secondaryLabel
        }
        return attributes
    }

    private func clamped(_ range: NSRange, for value: String) -> NSRange {
        let length = (value as NSString).length
        let location = min(max(range.location, 0), length)
        return NSRange(
            location: location,
            length: min(max(range.length, 0), length - location)
        )
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ChecklistItemTextView
        var appliedCaretToken: UUID?
        var lastCompletionState: Bool?

        init(parent: ChecklistItemTextView) {
            self.parent = parent
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            parent.focusedItemID = parent.itemID
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            if parent.focusedItemID == parent.itemID {
                parent.focusedItemID = nil
            }
        }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text
            textView.typingAttributes = parent.typingAttributes
            textView.invalidateIntrinsicContentSize()
        }

        func textView(
            _ textView: UITextView,
            shouldChangeTextIn range: NSRange,
            replacementText replacement: String
        ) -> Bool {
            guard replacement == "\n", textView.markedTextRange == nil else {
                return true
            }
            parent.text = textView.text
            parent.onReturn(range)
            return false
        }
    }
}
