#import <XCTest/XCTest.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testTwoPlusTwoEqualsFive {
  XCTAssertEqual(2 + 2, 5, @"Two plus two equals five");
}

@end
