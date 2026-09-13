import XCTest
@testable import Terra
final class AccountTests: XCTestCase {
    func testPasswordDerivationMatchesIndependentVector() {
        let key = LocalAccount.derive("password",salt: Data("salt".utf8))
        XCTAssertEqual(key?.map { String(format: "%02x",$0) }.joined(),"9d5f68774306eaee6c79c5b4d3a263907f81c55d4daa1c50585e1e849065e090")
        XCTAssertNotEqual(key,LocalAccount.derive("wrong-password",salt: Data("salt".utf8)))
        XCTAssertNotEqual(key,LocalAccount.derive("password",salt: Data("different-salt".utf8)))
    }
}
