import XCTest
@testable import Terra
final class StreakTests: XCTestCase {
    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(secondsFromGMT: 0)!; return c }
    private func date(_ day: Int, month: Int = 9) -> Date { calendar.date(from: DateComponents(year: 2026, month: month, day: day))! }
    func testYesterdayDoesNotBreakSeriesBeforeTodayEnds() {
        let s = WalkingStreak.status(walked: ["2026-09-10","2026-09-11"], restores: [], now: date(12), calendar: calendar)
        XCTAssertEqual(s.count,2); XCTAssertFalse(s.canRestore); XCTAssertFalse(s.walkedToday)
    }
    func testSingleMissCanBeRestoredOnlyOnce() {
        let walked: Set<String> = ["2026-09-10","2026-09-12"]
        XCTAssertTrue(WalkingStreak.status(walked: walked, restores: [], now: date(12), calendar: calendar).canRestore)
        let result = WalkingStreak.status(walked: walked, restores: [StreakRestore(day: "2026-09-11",usedOn: "2026-09-12")], now: date(12),calendar: calendar)
        XCTAssertEqual(result.count,3); XCTAssertEqual(result.remaining,2); XCTAssertFalse(result.canRestore)
    }
    func testMonthlyLimitAndReset() {
        let used = [1,3,5].map { StreakRestore(day: "2026-09-0\($0)", usedOn: "2026-09-0\($0+1)") }
        XCTAssertFalse(WalkingStreak.status(walked: ["2026-09-10"], restores: used, now: date(12), calendar: calendar).canRestore)
        XCTAssertEqual(WalkingStreak.status(walked: [], restores: used, now: date(1,month: 10), calendar: calendar).remaining,3)
        XCTAssertFalse(WalkingStreak.status(walked: ["2026-09-09"], restores: [], now: date(12), calendar: calendar).canRestore)
    }
}
