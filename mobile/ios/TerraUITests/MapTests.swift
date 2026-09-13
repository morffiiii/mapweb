import XCTest
final class MapTests: XCTestCase {
    func testNativeMapAndControls() {
        let app = XCUIApplication(); app.launch()
        addUIInterruptionMonitor(withDescription: "Location") { alert in
            for title in ["Allow While Using App", "Разрешить при использовании"] { if alert.buttons[title].exists { alert.buttons[title].tap(); return true } }
            return false
        }
        app.tap()
        XCTAssertTrue(app.maps.firstMatch.waitForExistence(timeout: 15))
        app.maps.firstMatch.swipeLeft(); app.maps.firstMatch.pinch(withScale: 1.5, velocity: 1)
        XCTAssertTrue(app.buttons["startRecording"].exists)
        XCTAssertFalse(app.staticTexts["Демо"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "Native map"; shot.lifetime = .keepAlways; add(shot)
    }
}
