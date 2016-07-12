#import <XCTest/XCTest.h>

@interface FooXCUITest : XCTestCase
@end

@implementation FooXCUITest

- (void)testThisWillBeSkipped
{
  XCTAssertTrue(false);
}

@end
