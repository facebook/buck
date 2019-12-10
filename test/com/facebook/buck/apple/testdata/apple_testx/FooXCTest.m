#import <XCTest/XCTest.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testTwoPlusTwoEqualsFour {
  XCTAssertEqual(2 + 2, 4, @"Two plus two equals four");
}

@end
