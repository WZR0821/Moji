import PhotosUI
import SwiftUI
import Vision

// MARK: - 首页录入建议

/// What was read out of a plan the user just typed, shown under that row.
/// Nothing changes until one of its buttons is tapped.
struct EntrySuggestionStrip: View {
    let suggestion: EntrySuggestion
    let apply: () -> Void
    let split: () -> Void
    let dismiss: () -> Void

    private var chips: [String] { suggestion.chips() }

    var body: some View {
        HStack(spacing: 8) {
            DraftSeal(size: 18, seed: 9)

            VStack(alignment: .leading, spacing: 2) {
                if let title = suggestion.title {
                    Text(title)
                        .font(.footnote)
                        .foregroundStyle(Color.planPrimary)
                        .lineLimit(1)
                }
                if !chips.isEmpty {
                    Text(chips.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                        .lineLimit(1)
                } else if suggestion.parts.count >= 2 {
                    Text("这句话里像是 \(suggestion.parts.count) 件事")
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if suggestion.parts.count >= 2 {
                stripButton("拆成 \(suggestion.parts.count) 条", identifier: "entry.suggestion.split", action: split)
            }
            if suggestion.hasDetails {
                stripButton("应用", identifier: "entry.suggestion.apply", action: apply)
            }
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Color.planSecondary.opacity(0.7))
                    .frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("不用这条建议")
        }
        .padding(.leading, 10)
        .padding(.trailing, 2)
        .padding(.vertical, 4)
        .inkDraftSurface(cornerRadius: 12)
        .accessibilityIdentifier("entry.suggestion")
    }

    private func stripButton(_ title: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.planPrimary)
                .padding(.horizontal, 10)
                .frame(minHeight: 30)
                .background(Color.planPrimary.opacity(0.07), in: Capsule())
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - 从文字或截图添加

/// Text in a picture, read on the device. The picture itself is never sent.
enum LocalTextRecognizer {
    static func text(in image: UIImage) async throws -> String {
        guard let cgImage = image.cgImage else { return "" }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let request = VNRecognizeTextRequest()
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = true
                request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
                do {
                    try VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:]).perform([request])
                    let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
                    continuation.resume(returning: lines.joined(separator: "\n"))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .upMirrored: self = .upMirrored
        case .downMirrored: self = .downMirrored
        case .leftMirrored: self = .leftMirrored
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}

struct PlanImportView: View {
    @ObservedObject var store: PlanStore

    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var photo: PhotosPickerItem?
    @State private var isReadingPhoto = false
    @State private var failure: String?
    @State private var showsDrafts = false

    private var cleanText: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var isTooLong: Bool { cleanText.count > AIDrafting.maximumInputCharacters }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("粘贴一段通知、聊天记录或会议纪要，或者选一张截图，把里面要做的事找出来。")
                        .font(.subheadline)
                        .foregroundStyle(Color.planSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    ZStack(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("把文字粘贴到这里……")
                                .font(.body)
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 15)
                                .padding(.vertical, 18)
                                .allowsHitTesting(false)
                        }
                        TextEditor(text: $text)
                            .font(.body)
                            .lineSpacing(4)
                            .scrollContentBackground(.hidden)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 10)
                            .frame(minHeight: 220)
                            .accessibilityIdentifier("import.text")
                    }
                    .inkPaperSurface(cornerRadius: 16)

                    HStack(spacing: 10) {
                        PasteButton(payloadType: String.self) { strings in
                            let pasted = strings.joined(separator: "\n")
                            text = text.isEmpty ? pasted : text + "\n" + pasted
                        }
                        .labelStyle(.titleAndIcon)
                        .buttonBorderShape(.capsule)
                        .tint(Color.planPrimary)

                        PhotosPicker(selection: $photo, matching: .images) {
                            Label("选截图", systemImage: "photo")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Color.planPrimary)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 36)
                                .background(Color.planPrimary.opacity(0.07), in: Capsule())
                        }

                        if isReadingPhoto { InkDraftWaiting(text: "正在认字") }
                        Spacer(minLength: 0)
                        Text("\(cleanText.count)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(isTooLong ? Color.planVermilion : Color.planSecondary.opacity(0.7))
                    }

                    if let failure {
                        Text(failure)
                            .font(.footnote)
                            .foregroundStyle(Color.planVermilion)
                    } else if isTooLong {
                        Text("内容太长了，请缩短到 6,000 字以内。")
                            .font(.footnote)
                            .foregroundStyle(Color.planVermilion)
                    } else {
                        Text("截图里的字在本机识别，只把文字发给模型。")
                            .font(.caption)
                            .foregroundStyle(Color.planSecondary.opacity(0.8))
                    }
                }
                .padding(.horizontal, PlanLayout.pageHorizontalPadding)
                .padding(.vertical, 14)
            }
            .scrollDismissesKeyboard(.interactively)
            .background { InkWashBackground() }
            .navigationTitle("从文字或截图添加")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("整理") { showsDrafts = true }
                        .fontWeight(.semibold)
                        .disabled(cleanText.isEmpty || isTooLong || isReadingPhoto)
                        .accessibilityIdentifier("import.run")
                }
            }
            .navigationDestination(isPresented: $showsDrafts) {
                let source = cleanText
                PlanDraftPage(store: store, title: "计划草稿", subject: "从这段文字里找到的事",
                              waitingText: "正在找要做的事",
                              load: { try await AIAssistant.shared.importPlans(source) },
                              close: { dismiss() })
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task { await read(item) }
            }
            .onAppear {
#if DEBUG
                if ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] == "ai-import-drafts", text.isEmpty {
                    text = "周五前把志望理由書终稿交上去，另外约一下体检，今天记得给老师回邮件确认面谈时间。"
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showsDrafts = true }
                }
#endif
            }
        }
    }

    private func read(_ item: PhotosPickerItem) async {
        isReadingPhoto = true
        failure = nil
        defer { isReadingPhoto = false; photo = nil }
        do {
            guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                failure = "这张图片读不出来，换一张试试。"
                return
            }
            let found = try await LocalTextRecognizer.text(in: image)
            guard !found.isEmpty else {
                failure = "这张图里没有认出文字。"
                return
            }
            text = text.isEmpty ? found : text + "\n" + found
        } catch {
            failure = "这张图片读不出来，换一张试试。"
        }
    }
}

// MARK: - 整理成清单

/// Checklist items drafted from a memo's text. The memo is only changed when
/// the user accepts, and then only inside the editor that is still unsaved.
struct ChecklistDraftSheet: View {
    let subject: String
    let load: @MainActor () async throws -> [String]
    let accept: ([String]) -> Void

    private struct Row: Identifiable, Equatable {
        let id = UUID()
        var text: String
        var isSelected = true
    }

    @Environment(\.dismiss) private var dismiss
    @State private var rows: [Row] = []
    @State private var failure: String?
    @State private var isLoading = true
    @State private var attempt = 0

    private var selected: [String] {
        rows.filter(\.isSelected).map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(subject)
                        .font(.subheadline)
                        .foregroundStyle(Color.planSecondary)
                        .lineLimit(2)
                        .padding(.horizontal, 2)

                    if isLoading {
                        InkDraftWaiting(text: "正在整理")
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .inkDraftSurface()
                    } else if let failure {
                        DraftFailureView(message: failure) { attempt += 1 }
                    } else {
                        HStack(spacing: 7) {
                            DraftSeal(size: 20, seed: 4)
                            Text("这是清单草稿。用了它，正文会换成清单；保存前可以还原。")
                                .font(.footnote)
                                .foregroundStyle(Color.planSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal, 2)

                        VStack(spacing: 0) {
                            ForEach($rows) { $row in
                                HStack(alignment: .top, spacing: 8) {
                                    Button {
                                        row.isSelected.toggle()
                                    } label: {
                                        Image(systemName: row.isSelected ? "checkmark.circle.fill" : "circle")
                                            .font(.system(size: 20))
                                            .foregroundStyle(row.isSelected ? Color.planPrimary : Color.planSecondary.opacity(0.6))
                                            .frame(width: 32, height: 32)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(row.isSelected ? "不要这一项" : "要这一项")

                                    TextField("清单项", text: $row.text, axis: .vertical)
                                        .lineLimit(1...3)
                                        .padding(.top, 5)
                                        .opacity(row.isSelected ? 1 : 0.5)
                                }
                                .padding(.vertical, 4)
                                if row.id != rows.last?.id {
                                    Divider().padding(.leading, 40).opacity(0.5)
                                }
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .inkDraftSurface()
                    }
                }
                .padding(.horizontal, PlanLayout.pageHorizontalPadding)
                .padding(.vertical, 14)
            }
            .background { InkWashBackground() }
            .navigationTitle("整理成清单")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("用这份清单") {
                        accept(selected)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(isLoading || selected.isEmpty)
                    .accessibilityIdentifier("checklist.draft.accept")
                }
            }
        }
        .task(id: attempt) { await run() }
        .presentationDetents([.medium, .large])
    }

    private func run() async {
        isLoading = true
        failure = nil
        do {
            let items = try await load()
            guard !Task.isCancelled else { return }
            rows = items.map { Row(text: $0) }
        } catch {
            guard !Task.isCancelled else { return }
            failure = error.draftMessage
        }
        isLoading = false
    }
}

// MARK: - 倒数日的准备计划

/// Shown once under the mode cards after a day is added. It only asks; the
/// drafting starts when the button is tapped.
struct PreparationOfferStrip: View {
    let title: String
    let daysLeft: Int
    let accept: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            DraftSeal(size: 18, seed: 12)

            VStack(alignment: .leading, spacing: 2) {
                Text("“\(title)”还有 \(daysLeft) 天")
                    .font(.footnote)
                    .foregroundStyle(Color.planPrimary)
                    .lineLimit(1)
                Text("要不要把准备的事排进这几天")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: accept) {
                Text("拟准备计划")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                    .padding(.horizontal, 10)
                    .frame(minHeight: 30)
                    .background(Color.planPrimary.opacity(0.07), in: Capsule())
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("preparation.offer.accept")

            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Color.planSecondary.opacity(0.7))
                    .frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("不用")
        }
        .padding(.leading, 10)
        .padding(.trailing, 2)
        .padding(.vertical, 4)
        .inkDraftSurface(cornerRadius: 12)
        .accessibilityIdentifier("preparation.offer")
    }
}

// MARK: - 周复盘草稿

/// The points a drafted reflection rests on, each naming the plans behind it.
struct ReflectionEvidenceList: View {
    let evidence: [ReflectionDraft.Evidence]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(evidence) { item in
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.point)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.planPrimary)
                    Text("依据：" + item.planTitles.joined(separator: "、"))
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
