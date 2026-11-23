import Foundation
import UIKit

/// One thing shared into Moji from another app and not yet turned into plans.
struct ShareInboxItem: Codable, Equatable, Identifiable {
    var id: String
    /// Seconds since 1970.
    var createdAt: Double
    var text: String?
    /// File name of the picture beside the JSON, e.g. `<uuid>.jpg`.
    var image: String?
}

/// 分享面板的收件箱: the share extension writes, the app reads one item at a
/// time and deletes it once it has been read. Kept in the App Group
/// container (the app's own Application Support where there is none), so the
/// extension and the app see the same folder.
enum ShareInbox {
    static let folderName = "ShareInbox"
    static let textLimit = 6_000
    static let imageLongEdge: CGFloat = 2_048

    static var directory: URL? {
        let fileManager = FileManager.default
        let group = AppGroupResolver.resolveForCurrentProcess()
        let base = fileManager.containerURL(forSecurityApplicationGroupIdentifier: group)
            ?? (try? fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true))
        guard let base else { return nil }
        let folder = base.appendingPathComponent(folderName, isDirectory: true)
        try? fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// Text past 6,000 characters is cut; only the first picture is kept.
    @discardableResult
    static func add(text: String?, image: UIImage?, now: Date = Date()) throws -> ShareInboxItem {
        guard let directory else { throw CocoaError(.fileNoSuchFile) }
        let id = UUID().uuidString
        let clean = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        var item = ShareInboxItem(id: id, createdAt: now.timeIntervalSince1970,
                                  text: clean.flatMap { $0.isEmpty ? nil : String($0.prefix(textLimit)) },
                                  image: nil)
        if let image, let data = resized(image).jpegData(compressionQuality: 0.85) {
            let name = "\(id).jpg"
            try data.write(to: directory.appendingPathComponent(name), options: .atomic)
            item.image = name
        }
        guard item.text != nil || item.image != nil else { throw CocoaError(.fileReadCorruptFile) }
        let data = try JSONEncoder().encode(item)
        try data.write(to: directory.appendingPathComponent("\(id).json"), options: .atomic)
        return item
    }

    /// Everything waiting, oldest first.
    static func items() -> [ShareInboxItem] {
        guard let directory,
              let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        return names.filter { $0.hasSuffix(".json") }
            .compactMap { name in
                (try? Data(contentsOf: directory.appendingPathComponent(name)))
                    .flatMap { try? JSONDecoder().decode(ShareInboxItem.self, from: $0) }
            }
            .sorted { $0.createdAt < $1.createdAt }
    }

    static func oldest() -> ShareInboxItem? { items().first }

    static func image(for item: ShareInboxItem) -> UIImage? {
        guard let name = item.image, let directory else { return nil }
        return UIImage(contentsOfFile: directory.appendingPathComponent(name).path)
    }

    static func remove(_ item: ShareInboxItem) {
        guard let directory else { return }
        let fileManager = FileManager.default
        try? fileManager.removeItem(at: directory.appendingPathComponent("\(item.id).json"))
        if let name = item.image { try? fileManager.removeItem(at: directory.appendingPathComponent(name)) }
    }

    static func removeAll() {
        for item in items() { remove(item) }
    }

    static func resized(_ image: UIImage) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > imageLongEdge, longest > 0 else { return image }
        let scale = imageLongEdge / longest
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
