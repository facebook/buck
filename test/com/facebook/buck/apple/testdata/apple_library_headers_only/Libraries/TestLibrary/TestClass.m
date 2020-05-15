#import "TestClass.h"

#import <Analytics/Analytics.h>

@implementation TestClass

+ (int)answer {
  logAnalytics(@"42");
  return 42;
}

@end
