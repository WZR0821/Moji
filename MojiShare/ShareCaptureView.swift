import SwiftUI
import UIKit

/// What the share sheet has read so far. The extension fills it; the app's
/// debug build fills it with a sample to photograph the same screen.
@MainActor
final class ShareCaptureModel: ObservableObject {
    @Published var text: String?
    @Published var image: UIImage?
    @Published var isLoading = true

    var isUnreadable: Bool { !isLoading && text == nil && image == nil }

    init(text: String? = nil, image: UIImage? = nil, isLoading: Bool = true) {
        self.text = text
        self.image = image
        self.isLoading = isLoading
    }
}

/// 收进墨记: the text or picture, one button that keeps it for Moji, and the
/// sentence saying nothing is added until the drafts are confirmed. Nothing
/// is sent from here.
struct ShareCaptureView: View {
    @ObservedObject var model: ShareCaptureModel
    /// Keeps what was read; false when it could not be kept.
    var keep: () -> Bool
    var cancel: () -> Void
    var done: () -> Void

    @State private var isSaved = false
    @State private var failed = false

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(ShareInk.background.ignoresSafeArea())
                .navigationTitle("收进墨记")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if !isSaved {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("取消", action: cancel)
                                .accessibilityIdentifier("share.cancel")
                        }
                    }
                }
        }
        .tint(ShareInk.primary)
        .accessibilityIdentifier("share.capture")
    }

    @ViewBuilder
    private var content: some View {
        if isSaved {
            VStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 46, weight: .regular))
                    .foregroundStyle(ShareInk.primary)
                Text("已收进墨记")
                    .font(.headline)
                    .foregroundStyle(ShareInk.primary)
            }
            .transition(.opacity)
            .accessibilityIdentifier("share.saved")
        } else if model.isLoading {
            ProgressView()
        } else if model.isUnreadable || failed {
            VStack(spacing: 18) {
                Text("没能读到可以收进的文字或图片。")
                    .font(.body)
                    .foregroundStyle(ShareInk.primary)
                    .multilineTextAlignment(.center)
                Button(action: cancel) {
                    Text("好")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(ShareInk.background)
                        .frame(width: 120, height: 44)
                        .background(ShareInk.primary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(24)
            .accessibilityIdentifier("share.unreadable")
        } else {
            VStack(alignment: .leading, spacing: 18) {
                preview
                Spacer(minLength: 0)
                VStack(spacing: 9) {
                    Button(action: save) {
                        Text("收进墨记")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(ShareInk.background)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(ShareInk.primary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("share.keep")
                    Text("打开墨记时会先整理成计划草稿，你确认后才加入。")
                        .font(.footnote)
                        .foregroundStyle(ShareInk.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 14)
            .padding(.bottom, 18)
        }
    }

    @ViewBuilder
    private var preview: some View {
        if let image = model.image {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: 260)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(ShareInk.primary.opacity(0.12), lineWidth: 0.75)
                }
                .accessibilityLabel("要收进的图片")
        }
        if let text = model.text {
            Text(text)
                .font(.body)
                .foregroundStyle(ShareInk.primary)
                .lineLimit(6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(ShareInk.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(ShareInk.primary.opacity(0.10), lineWidth: 0.75)
                }
                .accessibilityIdentifier("share.preview")
        }
    }

    private func save() {
        guard keep() else {
            failed = true
            return
        }
        withAnimation(.easeOut(duration: 0.2)) { isSaved = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { done() }
    }
}

/// The app's ink and paper, repeated here so the extension needs none of the
/// app's other files.
enum ShareInk {
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.065, green: 0.061, blue: 0.055, alpha: 1)
            : UIColor(red: 0.968, green: 0.957, blue: 0.929, alpha: 1)
    })
    static let card = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.135, green: 0.125, blue: 0.112, alpha: 0.94)
            : UIColor(red: 1.0, green: 0.996, blue: 0.985, alpha: 0.88)
    })
    static let primary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.93, green: 0.91, blue: 0.86, alpha: 1)
            : UIColor(red: 0.141, green: 0.137, blue: 0.129, alpha: 1)
    })
    static let secondary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.67, green: 0.64, blue: 0.58, alpha: 1)
            : UIColor(red: 0.427, green: 0.412, blue: 0.384, alpha: 1)
    })
}
