#import <Foundation/Foundation.h>
#import <Foo/Foo-Swift.h>

@interface Bar: NSObject
@end

@implementation Bar

- (void)test {
  [Foo printString];
}

@end