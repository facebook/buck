#import <XCTest/XCTest.h>

#import <Lib/Lib-Swift.h>

@interface LibTest : XCTestCase
@end

@implementation LibTest

- (void)testHello {
  XCTAssertEqualObjects(@"hello", [Dummy hello], @"Hello expected to be true");
}

@end
