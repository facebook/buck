#import "MD3TestClass.h"
#import "MixedDependency3-Swift.h"
#import "MixedDependency1/MixedDep1.h"
#import "MixedDependency2/MixedDep2.h"

@implementation MD3TestClass

+ (NSString *)answer {
  return NSStringFromClass([self class]);
}

+ (NSString *)fooBar {
  Foo *foo = [Foo new];
  return [foo bar];
}

+ (NSString *)md1Test {
  return [MD1TestClass answer];
  // return @"";
}

+ (NSString *)md2Test {
  return [MD2TestClass answer];
  // return @"";
}

@end
