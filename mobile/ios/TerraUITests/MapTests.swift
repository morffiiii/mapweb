import XCTest
final class MapTests: XCTestCase {
    func testNativeMapAndControls() {
        let app = XCUIApplication()
        continueAfterFailure = false
        addUIInterruptionMonitor(withDescription: "Location") { alert in
            for title in ["Allow While Using App", "Разрешить при использовании"] { if alert.buttons[title].exists { alert.buttons[title].tap(); return true } }
            return false
        }
        app.launch()
        if app.buttons["Создать профиль"].waitForExistence(timeout: 5) {
            XCTAssertFalse(app.buttons["Продолжить без регистрации"].exists)
            tap(app.buttons["Создать профиль"],in: app)
            let email = app.textFields["Почта"]; email.tap(); email.typeText("ui-test@example.com")
            let password = app.secureTextFields["Пароль · от 8 символов"]; password.tap(); password.typeText("Terra-test-2026")
            tap(app.buttons["Зарегистрироваться"],in: app)
            for _ in 0..<3 { tap(app.buttons["Пропустить"],in: app) }
            capture(app,"00-welcome"); tap(app.buttons["Открыть мой мир"],in: app)
        }
        app.tap()
        continueAfterFailure = false
        let nativeMap = app.descendants(matching: .any).matching(identifier: "nativeMap").firstMatch
        XCTAssertTrue(nativeMap.waitForExistence(timeout: 15))
        let gps = app.staticTexts["gpsStatus"]
        expectation(for: NSPredicate(format: "label BEGINSWITH 'GPS'"), evaluatedWith: gps)
        waitForExpectations(timeout: 30)
        capture(app, "01-map")
        nativeMap.coordinate(withNormalizedOffset: CGVector(dx: 0.5,dy: 0.38)).press(forDuration: 1.2)
        let placeTitle = app.textFields["placeTitle"]
        XCTAssertTrue(placeTitle.waitForExistence(timeout: 10)); placeTitle.tap(); placeTitle.typeText("Тестовое место")
        let description = app.textViews["Описание места"]; description.tap(); description.typeText("Заметка с удобными отступами")
        capture(app,"01-place-editor")
        tap(app.buttons["Сохранить место"],in: app)
        XCTAssertTrue(app.staticTexts["Тестовое место"].waitForExistence(timeout: 10))
        app.buttons.matching(identifier: "Закрыть").firstMatch.tap()
        let initial = nativeMap.value as? String
        nativeMap.swipeLeft()
        let moved = NSPredicate { _,_ in (nativeMap.value as? String) != initial }
        expectation(for: moved, evaluatedWith: nil); waitForExpectations(timeout: 10)
        nativeMap.pinch(withScale: 1.5, velocity: 1)
        XCTAssertTrue(app.buttons["startRecording"].exists)
        XCTAssertFalse(app.staticTexts["Демо"].exists)
        app.buttons["startRecording"].tap()
        XCTAssertTrue(app.buttons["Завершить"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["metric-km"].exists)
        XCTAssertTrue(app.staticTexts["metric-energy"].exists)
        XCTAssertFalse(app.segmentedControls["travelModes"].isHittable)
        capture(app,"01-active-dashboard")
        app.buttons["startRecording"].tap()
        XCTAssertEqual(app.buttons["startRecording"].label, "Продолжить")
        app.buttons["Завершить"].tap()
        app.terminate(); app.launch()
        app.buttons["История"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'км'")).firstMatch.waitForExistence(timeout: 10))
        capture(app, "02-history")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'км'")).firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "historyRouteMap").firstMatch.waitForExistence(timeout: 10))
        capture(app,"02-route-map"); app.buttons.matching(identifier: "Закрыть").firstMatch.tap()
        app.buttons["Закрыть"].tap()
        app.buttons["Профиль"].tap(); capture(app,"03-profile")
        app.buttons["Закрыть"].tap()
        app.buttons["Слои"].tap(); capture(app,"04-layers")
        app.buttons["Закрыть"].tap()
        app.buttons["Настройки"].tap(); capture(app,"05-settings")
        app.buttons["Тёмная"].tap()
        app.buttons["startRecording"].tap()
        XCTAssertTrue(app.buttons["Завершить"].waitForExistence(timeout: 5))
        app.buttons["Завершить"].tap()
        app.buttons.matching(identifier: "Закрыть").firstMatch.tap()
        app.buttons["Профиль"].tap()
        tap(app.buttons["Личные данные"],in: app)
        let interest = app.buttons["Больше гулять"]
        tap(interest,in: app)
        XCTAssertEqual(interest.value as? String,"Выбрано")
        XCTAssertFalse(app.staticTexts["Как узнал о Terra"].exists)
        capture(app,"06-edit-interests")
        app.buttons.matching(identifier: "Закрыть").firstMatch.tap()
        tap(app.buttons["Выйти"],in: app)
        XCTAssertTrue(app.buttons["Войти"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Продолжить без регистрации"].exists)
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["Войти"].waitForExistence(timeout: 10))
    }
    private func capture(_ app: XCUIApplication, _ name: String) { let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = name; shot.lifetime = .keepAlways; add(shot) }
    private func tap(_ element: XCUIElement,in app: XCUIApplication) { XCTAssertTrue(element.waitForExistence(timeout: 10)); for _ in 0..<8 { if element.isHittable { break }; app.scrollViews.firstMatch.swipeUp() }; element.tap() }
}
