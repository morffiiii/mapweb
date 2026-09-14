import XCTest
@testable import Terra
final class LiveTrackingTests: XCTestCase {
    func testSpeedRejectsUnreliableSamplesAndExpires() {
        var speed = LiveSpeed(); speed.add(metersPerSecond: 2,accuracy: 0.3,time: 1000,maximum: 100)
        XCTAssertEqual(speed.value(now: 1000)!,7.2,accuracy: 0.01)
        speed.add(metersPerSecond: 50,accuracy: 10,time: 2000,maximum: 100)
        XCTAssertEqual(speed.value(now: 2000)!,7.2,accuracy: 0.01); XCTAssertNil(speed.value(now: 6000))
        speed.add(metersPerSecond: 0,accuracy: 0.5,time: 7000,maximum: 100); XCTAssertEqual(speed.value(now: 7000),0)
    }
    func testWrongModeStopsDiscoveryAndRecovers() {
        var guardrail = ModeSpeedGuard()
        for time in stride(from: 1000.0,through: 9000,by: 1000) { guardrail.update(kmh: 30,time: time,mode: .walk) }
        XCTAssertFalse(guardrail.allowsDiscovery); XCTAssertTrue(guardrail.warning)
        for time in stride(from: 10000.0,through: 13000,by: 1000) { guardrail.update(kmh: 5,time: time,mode: .walk) }
        XCTAssertTrue(guardrail.allowsDiscovery); XCTAssertFalse(guardrail.warning)
        guardrail.update(kmh: 30,time: 14000,mode: .bike); XCTAssertTrue(guardrail.allowsDiscovery)
    }
    func testExcludedPointsDoNotOpenMapAndSurviveSave() throws {
        let s = TrackSession(mode: .walk,startedAt: 0,endedAt: 10000,points: [TrackPoint(lat: 55,lng: 37,t: 1000,accuracy: 5,excludeDiscovery: true)],steps: 15)
        let restored = try JSONDecoder().decode(TrackSession.self,from: JSONEncoder().encode(s))
        XCTAssertTrue(Discovery.cells([restored]).isEmpty); XCTAssertEqual(restored.steps,15)
    }
    func testLegacyPhotoIsRetained() throws {
        let bytes = Data(#"{"id":"a","lat":55,"lng":37,"note":"Old place","photo":"old.jpg"}"#.utf8)
        let place = try JSONDecoder().decode(Place.self,from: bytes)
        XCTAssertEqual(place.attachments.first?.file,"old.jpg"); XCTAssertEqual(place.displayTitle,"Old place")
    }
}
