#import "Greeter.h"

@implementation Greeter
+ (void)sayHello:(NSString *)name {
  printf("Hello %s\n", [name cStringUsingEncoding:NSUTF8StringEncoding]);
}
@end
