import Foundation
import XCTest

@testable import SwiftCallsComplexObjC

class StringTests: XCTestCase {
    func testIsPresent() {
        XCTAssertEqual("Hello, World!", Foo().greeting())
    }
}
