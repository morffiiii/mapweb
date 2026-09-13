import XCTest
final class MapTests: XCTestCase {
    func testNativeMapAndControls() {
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Location") { alert in
            for title in ["Allow While Using App", "Разрешить при использовании"] { if alert.buttons[title].exists { alert.buttons[title].tap(); return true } }
            return false
        }
        app.launch(); app.tap()
        continueAfterFailure = false
        let nativeMap = app.descendants(matching: .any).matching(identifier: "nativeMap").firstMatch
        XCTAssertTrue(nativeMap.waitForExistence(timeout: 15))
        let gps = app.staticTexts["gpsStatus"]
        expectation(for: NSPredicate(format: "label BEGINSWITH 'GPS'"), evaluatedWith: gps)
        waitForExpectations(timeout: 30)
        let initial = nativeMap.value as? String
        nativeMap.swipeLeft()
        let moved = NSPredicate { _,_ in (nativeMap.value as? String) != initial }
        expectation(for: moved, evaluatedWith: nil); waitForExpectations(timeout: 10)
        nativeMap.pinch(withScale: 1.5, velocity: 1)
        XCTAssertTrue(app.buttons["startRecording"].exists)
        XCTAssertFalse(app.staticTexts["Демо"].exists)
        app.buttons["startRecording"].tap()
        XCTAssertTrue(app.buttons["Завершить"].waitForExistence(timeout: 30))
        app.buttons["startRecording"].tap()
        XCTAssertEqual(app.buttons["startRecording"].label, "Продолжить")
        app.buttons["Завершить"].tap()
        app.terminate(); app.launch()
        app.buttons["История"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'км'")).firstMatch.waitForExistence(timeout: 10))
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Native map"; shot.lifetime = .keepAlways; add(shot)
    }
}
