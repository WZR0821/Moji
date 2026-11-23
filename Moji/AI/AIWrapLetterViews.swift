import SwiftUI

// 晚间收尾, 今天的整理 and 本周来信. Like 今日提要 they are drafts on dashed
// paper with the 「稿」 seal: the first sentence is worked out on the device
// every time it is drawn, and nothing is written until the user taps.

// MARK: - 晚间收尾

/// One plan left this evening, as the card shows it.
struct EveningWrapRow: Identifiable, Equatable {
    var id: UUID
    var title: String
    var action: WrapAction
    /// The model's reason, shown only while its own arrangement stands.
    var note: String?
}

struct EveningWrapCard: View {
    let factsLine: String
    let secondLine: String
    /// The arrangement, in the order of the list; empty unless there is one.
    var rows: [EveningWrapRow] = []
    /// Plans past the six rows: 还有 K 件照常顺延.
    var hiddenCount = 0
    var isLoading = false
    let isReady: Bool
    var isEditing = false
    /// 今天墨记替你整理了 N 处 ›, left out at 0.
    var writesCount = 0
    var showsClose = true
    var choose: (UUID, WrapAction) -> Void = { _, _ in }
    var apply: () -> Void = {}
    var toggleEditing: () -> Void = {}
    var openIntro: () -> Void = {}
    var openWrites: () -> Void = {}
    var dismiss: () -> Void = {}

    private var trailingText: CGFloat { showsClose ? 10 : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                DraftSeal(size: 18, seed: 23)
                Text("收尾")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.planVermilion)
                Spacer(minLength: 0)
                if showsClose {
                    Button(action: dismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Color.planSecondary.opacity(0.7))
                            .frame(width: 32, height: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("今天不再显示收尾")
                    .accessibilityIdentifier("wrap.dismiss")
                }
            }

            Text(factsLine)
                .font(.footnote)
                .foregroundStyle(Color.planPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.trailing, trailingText)
                .accessibilityIdentifier("wrap.facts")

            Text(secondLine)
                .font(.footnote.weight(rows.isEmpty ? .regular : .medium))
                .foregroundStyle(Color.planPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.trailing, trailingText)
                .accessibilityIdentifier("wrap.second")

            if isReady, isLoading, rows.isEmpty {
                InkDraftWaiting(text: "在想剩下的怎么安排")
                    .transition(.opacity)
            }

            if !rows.isEmpty {
                VStack(alignment: .leading, spacing: 7) {
                    ForEach(rows) { row in
                        rowView(row)
                    }
                    if hiddenCount > 0 {
                        Text("还有 \(hiddenCount) 件照常顺延")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.planSecondary)
                    }
                }
                .padding(.leading, 2)
                .padding(.trailing, trailingText)
                .padding(.top, 1)
                .transition(.opacity)

                HStack(spacing: 8) {
                    DraftCapsuleButton(title: "照此处理", identifier: "wrap.apply", action: apply)
                    DraftCapsuleButton(title: isEditing ? "改好了" : "逐条改", identifier: "wrap.edit",
                                       action: toggleEditing)
                }
                .padding(.top, 2)
            }

            if !isReady {
                Button(action: openIntro) {
                    Text("开启后，墨记会替你安排剩下的 ›")
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                        .frame(minHeight: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("wrap.intro")
            }

            if writesCount > 0 {
                Button(action: openWrites) {
                    Text("今天墨记替你整理了 \(writesCount) 处 ›")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.planVermilion)
                        .frame(minHeight: 26)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("wrap.writes")
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, showsClose ? 2 : 12)
        .padding(.top, showsClose ? 4 : 10)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .inkDraftSurface(cornerRadius: 14)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("wrap.card")
    }

    private func rowView(_ row: EveningWrapRow) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(AIDailyBrief.shortTitle(row.title))
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Color.planPrimary)
                .lineLimit(1)
            if isEditing {
                HStack(spacing: 0) {
                    Menu {
                        ForEach(WrapAction.allCases) { action in
                            Button {
                                choose(row.id, action)
                            } label: {
                                if action == row.action {
                                    Label(action.title, systemImage: "checkmark")
                                } else {
                                    Text(action.title)
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 3) {
                            Text(row.action.title)
                                .font(.system(size: 12))
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .bold))
                        }
                        .foregroundStyle(Color.planVermilion)
                        .frame(minHeight: 26)
                        .contentShape(Rectangle())
                    }
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .accessibilityLabel("「\(row.title)」：\(row.action.title)，点一下换")
                    .accessibilityIdentifier("wrap.menu")
                    if let note = row.note {
                        Text(" · \(note)")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.planSecondary)
                            .lineLimit(1)
                    }
                }
            } else {
                (Text(row.action.title).foregroundStyle(Color.planVermilion)
                    + Text(row.note.map { " · \($0)" } ?? "").foregroundStyle(Color.planSecondary))
                    .font(.system(size: 12))
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: isEditing ? .contain : .combine)
    }
}

// MARK: - 今天的整理

/// Every write Moji made today, newest first, each one able to be taken back.
struct AIWritesView: View {
    @ObservedObject var store: PlanStore
    @ObservedObject private var log = AIWriteLog.shared
    @State private var notice: String?

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    var body: some View {
        let entries = log.entries(on: AIClock.now())
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("墨记今天写入的，都能在这里撤销；之后改过的计划保持不变。")
                    .font(.footnote)
                    .foregroundStyle(Color.planSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                if entries.isEmpty {
                    Text("今天墨记还没有写入。")
                        .font(.subheadline)
                        .foregroundStyle(Color.planSecondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 28)
                        .accessibilityIdentifier("writes.empty")
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                            if index > 0 {
                                InkBrushDivider(tint: .planPrimary, animated: false, seed: 40 + index)
                                    .opacity(0.22)
                                    .padding(.leading, 60)
                            }
                            row(entry)
                        }
                    }
                    .padding(.horizontal, 14)
                    .inkPaperSurface(cornerRadius: 14)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background { InkWashBackground() }
        .navigationTitle("今天的整理")
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottom) {
            if let notice {
                Text(notice)
                    .font(.footnote)
                    .padding(12)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 18)
                    .padding(.bottom, 10)
                    .allowsHitTesting(false)
                    .transition(.opacity)
                    .accessibilityIdentifier("writes.notice")
            }
        }
        .task(id: notice) {
            guard let notice else { return }
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled, self.notice == notice { withAnimation { self.notice = nil } }
        }
        .accessibilityIdentifier("writes.page")
    }

    private func row(_ entry: AIWriteEntry) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(Self.timeFormatter.string(from: entry.date))
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Color.planSecondary)
                .lineLimit(1)
                .fixedSize()
                .frame(minWidth: 40, alignment: .leading)
            Text(entry.summary)
                .font(.subheadline)
                .foregroundStyle(entry.undone ? Color.planSecondary : Color.planPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if entry.undone {
                Text("已撤销")
                    .font(.footnote)
                    .foregroundStyle(Color.planSecondary)
                    .frame(minWidth: 44, minHeight: 32)
            } else {
                Button {
                    if let text = store.undoWrite(entry.id) { withAnimation { notice = text } }
                } label: {
                    Text("撤销")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.planVermilion)
                        .frame(minWidth: 44, minHeight: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("撤销：\(entry.summary)")
                .accessibilityIdentifier("writes.undo")
            }
        }
        .padding(.vertical, 9)
        .accessibilityElement(children: .combine)
    }
}

/// 今天的整理 as a half sheet that can be pulled up.
struct AIWritesSheet: View {
    @ObservedObject var store: PlanStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            AIWritesView(store: store)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            dismiss()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Color.planSecondary)
                        }
                        .accessibilityLabel("关闭")
                        .accessibilityIdentifier("writes.close")
                    }
                }
        }
        .tint(Color.planPrimary)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - 本周来信

/// The head every form of the letter card shares.
private struct WeeklyLetterHeader: View {
    let range: String
    var showsClose = true
    var dismiss: () -> Void = {}

    var body: some View {
        HStack(spacing: 6) {
            DraftSeal(size: 18, seed: 29)
            Text("本周来信")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.planVermilion)
            Spacer(minLength: 6)
            Text(range)
                .font(.caption)
                .foregroundStyle(Color.planSecondary)
                .lineLimit(1)
            if showsClose {
                Button(action: dismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color.planSecondary.opacity(0.7))
                        .frame(width: 32, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("收起这封来信")
                .accessibilityIdentifier("letter.dismiss")
            } else {
                Spacer().frame(width: 10)
            }
        }
    }
}

/// 本周来信 on the 回顾 page: the letter, the wait for it, or (not set up) the teaser.
struct WeeklyLetterCard: View {
    enum Content: Equatable {
        case letter(WeeklyLetter, facts: String)
        case loading
        case teaser
    }

    let range: String
    let content: Content
    var showsReminder = false
    var showsClose = true
    var putIntoReflection: () -> Void = {}
    var draftNextWeek: () -> Void = {}
    var enableReminder: () -> Void = {}
    var openIntro: () -> Void = {}
    var dismiss: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            WeeklyLetterHeader(range: range, showsClose: showsClose, dismiss: dismiss)

            switch content {
            case let .letter(letter, facts):
                letterBody(letter, facts: facts)
            case .loading:
                InkDraftWaiting(text: "正在写这一周的来信")
                    .padding(.bottom, 2)
            case .teaser:
                Button(action: openIntro) {
                    Text("开启后，每周日晚上墨记会写一封这一周的来信 ›")
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minHeight: 28, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.trailing, 10)
                .accessibilityIdentifier("letter.intro")
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, showsClose ? 2 : 14)
        .padding(.top, showsClose ? 5 : 12)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .inkDraftSurface(cornerRadius: 16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("letter.card")
    }

    @ViewBuilder
    private func letterBody(_ letter: WeeklyLetter, facts: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(letter.title)
                .font(.headline)
                .foregroundStyle(Color.planPrimary)
                .lineLimit(1)
                .accessibilityIdentifier("letter.title")
            Text(facts + letter.body)
                .font(.system(size: 14))
                .foregroundStyle(Color.planPrimary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("letter.body")
            if let evidence = AIWeeklyLetter.evidenceLine(letter.evidence) {
                Text(evidence)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.planSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                DraftCapsuleButton(title: "放进复盘", identifier: "letter.reflection", action: putIntoReflection)
                DraftCapsuleButton(title: "拟下周计划", identifier: "letter.nextWeek", action: draftNextWeek)
            }
            .padding(.top, 2)
            if showsReminder {
                HStack(spacing: 8) {
                    Text("每周日 20:00 提醒来信")
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                    Spacer(minLength: 0)
                    Button(action: enableReminder) {
                        Text("开启提醒")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.planVermilion)
                            .padding(.horizontal, 8)
                            .frame(minHeight: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("letter.reminder")
                }
            }
        }
        .padding(.trailing, showsClose ? 12 : 0)
    }
}

/// 往期来信: every letter kept on this device, newest first.
struct WeeklyLetterArchiveView: View {
    @ObservedObject private var state = AIWeeklyLetterState.shared

    var body: some View {
        List {
            ForEach(state.newestFirst) { letter in
                NavigationLink {
                    WeeklyLetterDetailView(letter: letter)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(AIWeeklyLetter.monday(fromKey: letter.week).map { AIWeeklyLetter.rangeText($0) } ?? letter.week)
                            .font(.caption)
                            .foregroundStyle(Color.planSecondary)
                        Text(letter.title)
                            .font(.body)
                            .foregroundStyle(Color.planPrimary)
                            .lineLimit(1)
                    }
                    .padding(.vertical, 3)
                }
                .listRowBackground(Color.planCard)
                .accessibilityIdentifier("letter.archive.row")
            }
        }
        .scrollContentBackground(.hidden)
        .background { InkWashBackground() }
        .navigationTitle("往期来信")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("letter.archive")
    }
}

/// One kept letter, as it was written. Read only.
struct WeeklyLetterDetailView: View {
    let letter: WeeklyLetter

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 7) {
                    DraftSeal(size: 18, seed: 29)
                    Text(AIWeeklyLetter.monday(fromKey: letter.week).map { AIWeeklyLetter.rangeText($0) } ?? letter.week)
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                }
                Text(letter.title)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                Text(letter.facts + letter.body)
                    .font(.body)
                    .foregroundStyle(Color.planPrimary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                if let evidence = AIWeeklyLetter.evidenceLine(letter.evidence) {
                    Text(evidence)
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !letter.nextFocus.isEmpty {
                    InkBrushDivider(tint: .planPrimary, animated: false, seed: 61)
                        .opacity(0.3)
                    Text("下周关注：\(letter.nextFocus)")
                        .font(.subheadline)
                        .foregroundStyle(Color.planPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .inkPaperSurface(cornerRadius: 16)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
        }
        .background { InkWashBackground() }
        .navigationTitle("来信")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("letter.detail")
    }
}
