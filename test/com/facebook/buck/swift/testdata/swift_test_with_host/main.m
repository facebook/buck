#include <Foundation/Foundation.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#elif TARGET_OS_MAC
#import <Appkit/Appkit.h>
#endif


#import "dep-Swift.h"

int main(int argc, char * argv[]) {
    @autoreleasepool {
        #if TARGET_OS_IPHONE
        return UIApplicationMain(argc, argv, nil, NSStringFromClass([AppDelegate class]));
        #elif TARGET_OS_MAC
        NSApplication * application = [NSApplication sharedApplication];

        AppDelegate * appDelegate = [[AppDelegate alloc] init];
        [application setDelegate:appDelegate];
        [application run];
        #endif
    }
}
