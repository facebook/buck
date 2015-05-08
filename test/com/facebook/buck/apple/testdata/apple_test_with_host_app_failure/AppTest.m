#import <XCTest/XCTest.h>
#import <UIKit/UIKit.h>

#import "TestHostApp.h"

@interface AppTest : XCTestCase
@end

@implementation AppTest

- (void)testMagicValueShouldFail {
  TestHostApp *testHostApp = (TestHostApp *)[[UIApplication sharedApplication] delegate];
  XCTAssertEqual(23, [testHostApp magicValue], @"Test is expected to fail (23 != 42)");
}

@end
