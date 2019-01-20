#import <XCTest/XCTest.h>

#import "LibTest-Swift.h"

@interface LibTest : XCTestCase
@end

@implementation LibTest

- (void)testMagicValue {
  XCTAssertEqual(42, [Dummy magicValue], @"Magic value not exposed by Swift");
}

@end
