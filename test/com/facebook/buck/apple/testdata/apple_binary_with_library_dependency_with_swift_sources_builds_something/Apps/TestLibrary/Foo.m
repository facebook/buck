#import "Foo.h"

#import "TestLibrary-Swift.h"

@implementation Foo

+ (void)printSwiftObject {
  NSLog(@"%@", [Bar new]);
  [Bar printSwiftStruct];
}

@end
