#import <XCTest/XCTest.h>
#import <UIKit/UIKit.h>

#import "TestHostApp.h"

@interface AppTest : XCTestCase
@end

@implementation AppTest

- (void)testMagicValue {
  TestHostApp *testHostApp = (TestHostApp *)[[UIApplication sharedApplication] delegate];
  XCTAssertEqual(42, [testHostApp magicValue], @"Magic value not exposed by test host");
}

@end
