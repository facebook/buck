#import "MD1TestClass2.h"

@implementation MD1TestClass2

+ (NSString *)answer {
  return [NSString stringWithFormat:@"%@", NSStringFromClass(self)];
}

@end
