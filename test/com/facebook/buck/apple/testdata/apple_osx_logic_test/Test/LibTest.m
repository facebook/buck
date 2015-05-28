#import <XCTest/XCTest.h>

#import "Lib.h"

@interface LibTest : XCTestCase
@end

@implementation LibTest

- (void)testMagicValue {
  Lib *lib = [[[Lib alloc] init] autorelease];
  XCTAssertEqual(42, [lib magicValue], @"Magic value not exposed by lib");
}

@end
