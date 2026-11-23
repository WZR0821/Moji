import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The share extension: reads one piece of text, a link and the first
/// picture from the share sheet and keeps them in the inbox the app reads
/// the next time it opens. Nothing is sent anywhere from here.
final class ShareViewController: UIViewController {
    private let model = ShareCaptureModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let root = ShareCaptureView(
            model: model,
            keep: { [weak self] in self?.keep() ?? false },
            cancel: { [weak self] in self?.cancel() },
            done: { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        )
        let host = UIHostingController(rootView: root)
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        host.didMove(toParent: self)
        Task { @MainActor in await load() }
    }

    private func keep() -> Bool {
        (try? ShareInbox.add(text: model.text, image: model.image)) != nil
    }

    private func cancel() {
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }

    @MainActor
    private func load() async {
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        var texts: [String] = []
        var image: UIImage?
        for item in items {
            if let content = item.attributedContentText?.string.trimmingCharacters(in: .whitespacesAndNewlines),
               !content.isEmpty {
                texts.append(content)
            }
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    if image == nil { image = await Self.loadImage(provider) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    if let url = await Self.loadURL(provider) { texts.append(url) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    if let text = await Self.loadText(provider) { texts.append(text) }
                }
            }
        }
        var unique: [String] = []
        for text in texts where !unique.contains(text) { unique.append(text) }
        let joined = unique.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        model.text = joined.isEmpty ? nil : String(joined.prefix(ShareInbox.textLimit))
        model.image = image
        model.isLoading = false
    }

    private static func loadItem(_ provider: NSItemProvider, _ type: UTType) async -> NSSecureCoding? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                continuation.resume(returning: item)
            }
        }
    }

    private static func loadImage(_ provider: NSItemProvider) async -> UIImage? {
        switch await loadItem(provider, .image) {
        case let image as UIImage: return image
        case let data as Data: return UIImage(data: data)
        case let url as URL:
            guard let data = try? Data(contentsOf: url) else { return nil }
            return UIImage(data: data)
        default: return nil
        }
    }

    private static func loadURL(_ provider: NSItemProvider) async -> String? {
        switch await loadItem(provider, .url) {
        case let url as URL: return url.isFileURL ? nil : url.absoluteString
        case let text as String: return text
        default: return nil
        }
    }

    private static func loadText(_ provider: NSItemProvider) async -> String? {
        let value: String?
        switch await loadItem(provider, .plainText) {
        case let text as String: value = text
        case let text as NSAttributedString: value = text.string
        case let data as Data: value = String(data: data, encoding: .utf8)
        case let url as URL: value = try? String(contentsOf: url, encoding: .utf8)
        default: value = nil
        }
        let clean = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return clean.isEmpty ? nil : clean
    }
}
