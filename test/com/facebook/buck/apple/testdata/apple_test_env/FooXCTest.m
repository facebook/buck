#import <XCTest/XCTest.h>

@interface FooXCTest : XCTestCase
@end

@implementation FooXCTest

- (void)testEnvFooIsBar {
  XCTAssertEqualObjects(NSProcessInfo.processInfo.environment[@"FOO"], @"bar", @"FOO=bar");
}

@end
