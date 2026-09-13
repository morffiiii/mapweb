import XCTest
@testable import Terra
final class TrackingTests: XCTestCase {
    func testRejectsBadGPSAndSpeedSpikes() {
        let a = TrackPoint(lat: 55, lng: 37, t: 1000, accuracy: 5)
        XCTAssertFalse(TrackPoint(lat: 55, lng: 37.1, t: 2000, accuracy: 5).accepts(after: a, mode: .walk))
        XCTAssertFalse(TrackPoint(lat: 55, lng: 37.001, t: 10000, accuracy: 100).accepts(after: a, mode: .walk))
        XCTAssertTrue(TrackPoint(lat: 55, lng: 37.0001, t: 11000, accuracy: 5).accepts(after: a, mode: .walk))
    }
    func testPauseAndSignalLossDoNotConnect() {
        let a = TrackPoint(lat: 55, lng: 37, t: 1000, accuracy: 5)
        let b = TrackPoint(lat: 55, lng: 37.001, t: 11000, accuracy: 5, breakBefore: true)
        let c = TrackPoint(lat: 55, lng: 37.002, t: 100000, accuracy: 5)
        let s = TrackSession(mode: .walk, startedAt: 1000, endedAt: 110000, pausedMs: 10000, points: [a,b,c])
        XCTAssertEqual(s.distance, 0); XCTAssertEqual(s.duration(), 99)
    }
    func testBackupRoundTripAndValidation() throws {
        let s = TrackSession(mode: .bike, startedAt: 1000, endedAt: 3000, points: [TrackPoint(lat: 55, lng: 37, t: 2000, accuracy: 5)])
        let decoded = try JSONDecoder().decode(Backup.self, from: JSONEncoder().encode(Backup(sessions: [s])))
        try decoded.validate(); XCTAssertEqual(decoded.sessions[0].id, s.id)
        XCTAssertThrowsError(try Backup(sessions: [s,s]).validate())
        XCTAssertThrowsError(try TrackSession(mode: .walk, startedAt: 1000, endedAt: 500).validate())
    }
    func testDiscoveryDoesNotDoubleCountOverlap() {
        let s = TrackSession(mode: .walk, startedAt: 0, points: [TrackPoint(lat: 55, lng: 37, t: 1000, accuracy: 5)])
        XCTAssertFalse(Discovery.cells([s]).isEmpty)
        XCTAssertEqual(Discovery.cells([s]), Discovery.cells([s,s]))
    }
}
