import Foundation
import Security

/// Where API keys are kept. The app uses the keychain; tests and screenshot
/// scenarios pass an in-memory store so nothing real is read or written.
protocol AIKeyStoring {
    func read(account: String) throws -> String
    func write(_ key: String, account: String) throws
}

/// API keys in the device keychain: this device only, never in a backup file,
/// the App Group or the widget.
struct AIKeychainStore: AIKeyStoring {
    var service = "com.raydon.minuteplan.ai-keys"

    func read(account: String) throws -> String {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            throw AIRequestError.credentialUnavailable
        }
        return value
    }

    func write(_ key: String, account: String) throws {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.contains("\n"), !clean.contains("\r") else { throw AIRequestError.credentialUnavailable }
        if clean.isEmpty {
            let status = SecItemDelete(baseQuery(account) as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw AIRequestError.credentialUnavailable }
            return
        }
        let data = Data(clean.utf8)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(baseQuery(account) as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw AIRequestError.credentialUnavailable }
        var item = baseQuery(account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw AIRequestError.credentialUnavailable }
    }

    private func baseQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
}

/// Keys held in memory only.
final class AIMemoryKeyStore: AIKeyStoring {
    private var values: [String: String]
    init(_ values: [String: String] = [:]) { self.values = values }
    func read(account: String) throws -> String { values[account] ?? "" }
    func write(_ key: String, account: String) throws {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { values.removeValue(forKey: account) } else { values[account] = clean }
    }
}
