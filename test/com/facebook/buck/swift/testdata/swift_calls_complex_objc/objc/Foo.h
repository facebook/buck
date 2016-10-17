#import <Foundation/Foundation.h>

// make sure it's available from Foo-Bridging-Header.h
#import "SwiftCallsComplexObjC/Empty.h"

@interface Foo: NSObject

@property (nonatomic, readonly) NSString *name;

@end
