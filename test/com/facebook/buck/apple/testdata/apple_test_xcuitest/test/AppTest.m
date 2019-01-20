#import <XCTest/XCTest.h>

@interface AppTest : XCTestCase
@end

@implementation AppTest

- (void)testTwoPlusTwoEqualsFour {
  [[XCUIApplication new] launch];
  XCTAssertEqualObjects([[XCUIApplication new] label], @"TestTargetApp");
  XCTAssertEqual(2 + 2, 4, @"Two plus two equals four");
}

@end
