import Foundation
import XCTest

@testable import SwiftCallsComplexObjC

class StringTests: XCTestCase {
    func testIsPresent() {
        XCTAssertEquals("Hello, World!", Foo().greeting())
    }
}
