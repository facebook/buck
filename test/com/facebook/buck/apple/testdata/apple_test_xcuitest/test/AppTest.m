#import <XCTest/XCTest.h>
#import <UIKit/UIKit.h>

@interface AppTest : XCTestCase
@end

@implementation AppTest

// xctool doesn't run xcuitests
- (void)testNotExecuted {
  XCTAssertEqual(42, 1);
}

@end
