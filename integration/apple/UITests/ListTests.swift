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
            // The row leaves on the next update, not within the tap; wait as the other checks do.
            XCTAssertTrue(waitForNonExistence(app.buttons["Row child"], timeout: 5))
            app.buttons["Hide list"].tap()
            XCTAssertTrue(app.buttons["Show list"].waitForExistence(timeout: 5))
            app.buttons["Show list"].tap()
            XCTAssertTrue(app.buttons["Hide list"].waitForExistence(timeout: 5))
        }
    }

    /// Whether `element` stops existing within `timeout` seconds.
    private func waitForNonExistence(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }
}
