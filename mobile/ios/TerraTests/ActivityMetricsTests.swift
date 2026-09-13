import XCTest
@testable import Terra
final class ActivityMetricsTests: XCTestCase {
    private var walk: TrackSession { TrackSession(mode: .walk,startedAt: 1000,points: [TrackPoint(lat: 0,lng: 0,t: 1000,accuracy: 5),TrackPoint(lat: 0.0001,lng: 0,t: 11000,accuracy: 5)]) }
    func testFreshSpeedAndPause() {
        XCTAssertEqual(ActivityMetrics.speed(walk,now: 11000),4.003,accuracy: 0.02)
        XCTAssertEqual(ActivityMetrics.speed(walk,now: 30000),0)
        var paused = walk; paused.pausedAt = 11000; XCTAssertEqual(ActivityMetrics.speed(paused,now: 11000),0)
    }
    func testEnergyWeightAndFallback() {
        let base = ActivityMetrics.calories(walk,weight: 70)
        XCTAssertGreaterThan(base,0); XCTAssertEqual(ActivityMetrics.calories(walk,weight: 140),2*base,accuracy: 0.001)
        XCTAssertEqual(ActivityMetrics.calories(walk,weight: nil),base)
        XCTAssertEqual(ActivityMetrics.calories(walk,weight: .nan),base)
    }
    func testNoEnergyAcrossPauseOrInCar() {
        var s = walk; s.points[1].breakBefore = true; XCTAssertEqual(ActivityMetrics.calories(s,weight: nil),0)
        s = walk; s.points[1].t = 90000; XCTAssertEqual(ActivityMetrics.calories(s,weight: nil),0)
        s = walk; s.mode = .car; XCTAssertEqual(ActivityMetrics.calories(s,weight: nil),0)
    }
}
