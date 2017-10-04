import XCTest

class LibTest: XCTestCase {
  func testLib() {
    XCTAssertEqual(42, Lib().magicValue(), "Magic value expected to match")
  }
}