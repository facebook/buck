#import "MD2TestClass.h"
#import "MixedDependency2-Swift.h"
#import "MixedDependency1/MixedDep1.h"

@implementation MD2TestClass

+ (NSString *)answer {
  return NSStringFromClass([self class]);
}

+ (NSString *)fooBar {
  Foo *foo = [Foo new];
  return [foo bar];
}

+ (NSString *)md1Test {
  return [MD1TestClass answer];
}

@end
