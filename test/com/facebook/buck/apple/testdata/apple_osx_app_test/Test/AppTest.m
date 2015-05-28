#import <AppKit/AppKit.h>
#import <XCTest/XCTest.h>

#import "App.h"

@interface AppTest : XCTestCase
@end

@implementation AppTest

- (void)testMagicValue {
  App *app = (App *)[[NSApplication sharedApplication] delegate];
  XCTAssertEqual(42, [app magicValue], @"Magic value not exposed by host app");
}

@end
