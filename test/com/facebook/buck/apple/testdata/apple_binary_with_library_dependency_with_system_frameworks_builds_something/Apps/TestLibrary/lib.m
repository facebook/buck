#import <Foundation/Foundation.h>
#import <XCTest/XCTest.h>

CFStringRef answer() {
	return (CFStringRef)[NSString stringWithFormat:@"The answer is %d.", 42];
}
