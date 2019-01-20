#import <XCTest/XCTest.h>

@interface SpinningXCTest : XCTestCase
@end

@implementation SpinningXCTest

- (void)testThatNeverEnds {
   [NSThread sleepForTimeInterval:30.0f];
   while (true) { sleep(1); }
}

@end