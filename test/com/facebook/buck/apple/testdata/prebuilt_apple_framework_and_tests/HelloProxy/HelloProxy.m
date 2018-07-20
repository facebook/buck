#import "HelloProxy.h"

@implementation HelloProxy : Hello
- (NSString *)sayHelloFancily {
	return [NSString stringWithFormat:@"Fancy %@", [[super class] sayHello]];
}
@end