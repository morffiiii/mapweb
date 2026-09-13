import Foundation
import CommonCrypto
import Security

// Local credentials only: no server account, email verification or remote recovery.
enum LocalAccount {
    struct Record: Codable { var email: String; var salt: Data; var hash: Data }
    static let service = "app.terra.explore.local-account"
    static func record() -> Record? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?; guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(Record.self, from: data)
    }
    static func derive(_ password: String, salt: Data) -> Data? {
        let bytes = Array(password.utf8); var result = [UInt8](repeating: 0,count: 32)
        let status = bytes.withUnsafeBytes { pass in salt.withUnsafeBytes { s in
            CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2),pass.baseAddress?.assumingMemoryBound(to: Int8.self),bytes.count,s.baseAddress?.assumingMemoryBound(to: UInt8.self),salt.count,CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),210000,&result,32)
        } }
        return status == kCCSuccess ? Data(result) : nil
    }
    static func register(email: String,password: String) throws {
        guard record() == nil else { throw AccountError.exists }
        var salt = [UInt8](repeating: 0,count: 16); guard SecRandomCopyBytes(kSecRandomDefault,16,&salt) == errSecSuccess, let hash = derive(password,salt: Data(salt)) else { throw AccountError.storage }
        let bytes = try JSONEncoder().encode(Record(email: email.lowercased(),salt: Data(salt),hash: hash))
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,kSecAttrAccount as String: "local",kSecValueData as String: bytes,kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        guard SecItemAdd(query as CFDictionary,nil) == errSecSuccess else { throw AccountError.storage }
    }
    static func login(email: String,password: String) -> Bool {
        guard let r = record(), r.email == email.lowercased(), let actual = derive(password,salt: r.salt), actual.count == r.hash.count else { return false }
        return zip(actual,r.hash).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
    enum AccountError: LocalizedError { case exists, storage
        var errorDescription: String? { self == .exists ? "На этом телефоне уже есть профиль. Используй вход." : "Не удалось сохранить профиль." }
    }
}
