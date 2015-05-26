#import <XCTest/XCTest.h>
#import <UIKit/UIKit.h>

@interface AppTest : XCTestCase
@end

@implementation AppTest

- (void)testLabelText {
  UIView *rootView = [UIApplication sharedApplication].delegate.window.rootViewController.view;
  XCTAssert(
    [rootView isKindOfClass:[UILabel class]],
    @"Root view must be UILabel deserialized from XIB");
  NSString *viewLabel = ((UILabel *)rootView).text;
  XCTAssertEqualObjects(
    viewLabel,
    @"Hello world!",
    @"Root label text should be deserialized from XIB");
}

@end
