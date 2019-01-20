#import <XCTest/XCTest.h>

@interface BarXCTest : XCTestCase
@end

@implementation BarXCTest

- (void)testTwoPlusTwoEqualsFour {
  XCTAssertEqual(2 + 2, 4, @"Two plus two equals four");
}

@end
