import Foundation

/// Works out which App Group this install can actually open.
///
/// The IPA declares `group.com.raydon.minuteplan`. A sideloading tool signing
/// with the user's own Apple ID cannot register that exact identifier, so
/// SideStore and AltStore register `group.com.raydon.minuteplan.<TEAMID>`
/// instead and sign both the app and the widget with it. AltStore and
/// SideStore 0.6 also list the result in Info.plist under `ALTAppGroups`;
/// SideStore 0.7 only puts it in the code signature. Both places are read,
/// and a candidate is used only once its container really exists, so an
/// install without any App Group (LiveContainer, unsigned simulator builds)
/// keeps the original identifier and its sandbox fallback.
enum AppGroupResolver {
    static let declaredIdentifier = "group.com.raydon.minuteplan"
    static let infoPlistKey = "ALTAppGroups"

    static func resolveForCurrentProcess(
        bundle: Bundle = .main,
        fileManager: FileManager = .default
    ) -> String {
        let infoPlistGroups = bundle.object(forInfoDictionaryKey: infoPlistKey) as? [String] ?? []
        let signedGroups = bundle.executableURL.map {
            CodeSignatureEntitlements.applicationGroups(ofExecutableAt: $0)
        } ?? []
        return resolve(
            declared: declaredIdentifier,
            infoPlistGroups: infoPlistGroups,
            signedGroups: signedGroups
        ) { fileManager.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil }
    }

    static func resolve(
        declared: String,
        infoPlistGroups: [String],
        signedGroups: [String],
        isAvailable: (String) -> Bool
    ) -> String {
        candidates(
            declared: declared,
            infoPlistGroups: infoPlistGroups,
            signedGroups: signedGroups
        ).first(where: isAvailable) ?? declared
    }

    /// Groups derived from the declared one come first, shortest first, the
    /// same rule AltStore uses to map a declared group to its signed copy.
    /// The declared identifier is always the last resort.
    static func candidates(
        declared: String,
        infoPlistGroups: [String],
        signedGroups: [String]
    ) -> [String] {
        var seen = Set<String>()
        let groups = (infoPlistGroups + signedGroups).filter {
            !$0.isEmpty && seen.insert($0).inserted
        }
        let derived = groups.enumerated()
            .filter { isDerived($0.element, from: declared) }
            .sorted { ($0.element.count, $0.offset) < ($1.element.count, $1.offset) }
            .map(\.element)
        let others = groups.filter { !isDerived($0, from: declared) }
        var result = derived + others
        if !result.contains(declared) {
            result.append(declared)
        }
        return result
    }

    static func isDerived(_ group: String, from declared: String) -> Bool {
        group == declared || group.hasPrefix(declared + ".")
    }
}

/// Reads the entitlements embedded in a Mach-O code signature, the same
/// place SideStore itself reads them from. Every malformed or unexpected
/// layout yields `nil` instead of trapping.
enum CodeSignatureEntitlements {
    static let applicationGroupsKey = "com.apple.security.application-groups"

    private static let fatMagic: UInt32 = 0xCAFE_BABE
    private static let fatMagic64: UInt32 = 0xCAFE_BABF
    private static let machMagic: UInt32 = 0xFEED_FACE
    private static let machMagic64: UInt32 = 0xFEED_FACF
    private static let codeSignatureCommand: UInt32 = 0x1D
    private static let superBlobMagic: UInt32 = 0xFADE_0CC0
    private static let entitlementsBlobMagic: UInt32 = 0xFADE_7171
    private static let entitlementsSlot: UInt32 = 5

    static func applicationGroups(ofExecutableAt url: URL) -> [String] {
        guard let data = try? Data(contentsOf: url, options: .alwaysMapped),
              let entitlements = entitlements(inMachO: data) else {
            return []
        }
        return entitlements[applicationGroupsKey] as? [String] ?? []
    }

    static func entitlements(inMachO data: Data) -> [String: Any]? {
        let reader = ByteReader(data: data)
        guard let magic = reader.uint32(at: 0, bigEndian: true) else { return nil }
        guard magic == fatMagic || magic == fatMagic64 else {
            return thinEntitlements(reader, base: 0)
        }
        let is64 = magic == fatMagic64
        guard let count = reader.uint32(at: 4, bigEndian: true), count <= 32 else { return nil }
        let entrySize = is64 ? 32 : 20
        for index in 0..<Int(count) {
            let entry = 8 + index * entrySize
            let offset = is64
                ? reader.uint64(at: entry + 8, bigEndian: true)
                : reader.uint32(at: entry + 8, bigEndian: true).map(UInt64.init)
            guard let offset, offset < UInt64(data.count) else { continue }
            if let found = thinEntitlements(reader, base: Int(offset)) {
                return found
            }
        }
        return nil
    }

    private static func thinEntitlements(_ reader: ByteReader, base: Int) -> [String: Any]? {
        guard let magic = reader.uint32(at: base, bigEndian: false) else { return nil }
        let headerSize: Int
        switch magic {
        case machMagic64: headerSize = 32
        case machMagic: headerSize = 28
        default: return nil
        }
        guard let commandCount = reader.uint32(at: base + 16, bigEndian: false),
              let commandsSize = reader.uint32(at: base + 20, bigEndian: false) else {
            return nil
        }
        var cursor = base + headerSize
        let end = cursor + Int(commandsSize)
        for _ in 0..<min(Int(commandCount), 4096) {
            guard cursor + 8 <= end,
                  let command = reader.uint32(at: cursor, bigEndian: false),
                  let size = reader.uint32(at: cursor + 4, bigEndian: false),
                  size >= 8 else {
                return nil
            }
            if command == codeSignatureCommand {
                guard size >= 16,
                      let dataOffset = reader.uint32(at: cursor + 8, bigEndian: false),
                      let dataSize = reader.uint32(at: cursor + 12, bigEndian: false) else {
                    return nil
                }
                return superBlobEntitlements(
                    reader,
                    start: base + Int(dataOffset),
                    size: Int(dataSize)
                )
            }
            cursor += Int(size)
        }
        return nil
    }

    private static func superBlobEntitlements(
        _ reader: ByteReader,
        start: Int,
        size: Int
    ) -> [String: Any]? {
        guard reader.uint32(at: start, bigEndian: true) == superBlobMagic,
              let count = reader.uint32(at: start + 8, bigEndian: true),
              count <= 64 else {
            return nil
        }
        for index in 0..<Int(count) {
            let entry = start + 12 + index * 8
            guard let slot = reader.uint32(at: entry, bigEndian: true),
                  let offset = reader.uint32(at: entry + 4, bigEndian: true) else {
                return nil
            }
            guard slot == entitlementsSlot else { continue }
            let blob = start + Int(offset)
            guard Int(offset) < size,
                  reader.uint32(at: blob, bigEndian: true) == entitlementsBlobMagic,
                  let length = reader.uint32(at: blob + 4, bigEndian: true),
                  length > 8,
                  Int(offset) + Int(length) <= size,
                  let payload = reader.bytes(at: blob + 8, count: Int(length) - 8) else {
                return nil
            }
            return (try? PropertyListSerialization.propertyList(from: payload, format: nil))
                as? [String: Any]
        }
        return nil
    }
}

private struct ByteReader {
    let data: Data

    func bytes(at offset: Int, count: Int) -> Data? {
        guard offset >= 0, count >= 0, count <= data.count, offset <= data.count - count else {
            return nil
        }
        let start = data.startIndex + offset
        return data.subdata(in: start..<(start + count))
    }

    func uint32(at offset: Int, bigEndian: Bool) -> UInt32? {
        guard let raw = bytes(at: offset, count: 4) else { return nil }
        let value = raw.reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return bigEndian ? value : value.byteSwapped
    }

    func uint64(at offset: Int, bigEndian: Bool) -> UInt64? {
        guard let raw = bytes(at: offset, count: 8) else { return nil }
        let value = raw.reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        return bigEndian ? value : value.byteSwapped
    }
}
