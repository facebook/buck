#import <XCTest/XCTest.h>
#import <BuckTest/BuckTest.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testTwoPlusTwoEqualsFour {
  XCTAssertEqual([Hello sayHello], @"Hello", @"Must say hello");
}

@end
