#import "OD1TestClass2.h"

@implementation OD1TestClass2

+ (NSString *)answer {
  return [NSString stringWithFormat:@"%@", NSStringFromClass(self)];
}

@end
