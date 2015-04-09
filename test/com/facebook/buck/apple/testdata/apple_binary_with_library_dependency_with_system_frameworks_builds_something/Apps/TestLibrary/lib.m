#import <Foundation/Foundation.h>

CFStringRef answer() {
	return (CFStringRef)[NSString stringWithFormat:@"The answer is %d.", 42];
}
