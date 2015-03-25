#import <XCTest/XCTest.h>

@interface FooTest : XCTestCase
@end

@implementation FooTest

- (void)testFoo {
  XCTFail(@"whoops");
}

@end
