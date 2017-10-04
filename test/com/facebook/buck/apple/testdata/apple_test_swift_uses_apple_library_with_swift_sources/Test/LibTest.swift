import XCTest
import Lib

class LibTest: XCTestCase {
  func testLib() {
    XCTAssertEqual("hello", Dummy.hello(), "Hello expected to be true")
  }
}