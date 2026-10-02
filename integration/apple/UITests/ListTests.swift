import XCTest

@MainActor
final class ListTests: XCTestCase {
    func testInitiallyEmptyFormReplacementAndRemount() { exerciseContainer(useList: false) }
    func testInitiallyEmptyListReplacementAndRemount() { exerciseContainer(useList: true) }
    private func exerciseContainer(useList: Bool) {
        let app = XCUIApplication()
        app.launch()
        if useList { app.buttons["Use List"].tap() }
        for _ in 0..<5 {
            app.buttons["Populate"].tap()
            XCTAssertTrue(app.buttons["Row child"].waitForExistence(timeout: 5))
            app.buttons["Row child"].tap()
            XCTAssertTrue(app.buttons["Selected child"].waitForExistence(timeout: 5))
            app.buttons["Populate"].tap()
            XCTAssertTrue(app.buttons["Row child"].waitForExistence(timeout: 5))
            app.buttons["Empty"].tap()
            XCTAssertFalse(app.buttons["Row child"].exists)
            app.buttons["Hide list"].tap()
            XCTAssertTrue(app.buttons["Show list"].waitForExistence(timeout: 5))
            app.buttons["Show list"].tap()
            XCTAssertTrue(app.buttons["Hide list"].waitForExistence(timeout: 5))
        }
    }
}
