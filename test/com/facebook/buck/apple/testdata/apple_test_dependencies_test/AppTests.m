#import <Foundation/Foundation.h>
#import <XCTest/XCTest.h>

#import <Library/Library.h>

@interface AppTests : XCTestCase

@end

@implementation AppTests

+ (void)test
{
  assert([[Library hello] isEqualToString:@"world"]);
}

@end
