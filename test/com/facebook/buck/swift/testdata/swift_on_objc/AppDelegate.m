#import "AppDelegate.h"
#import <binary-Swift.h>
#import <UIKit/UIKit.h>
#import <dep1-Swift.h>

@interface AppDelegate ()

@end

@implementation AppDelegate


- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    [Test0 func0:@"message0"];

    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"From AppDelegate.m"
                                                    message:@"Dee dee doo doo."
                                                   delegate:self
                                          cancelButtonTitle:@"OK"
                                          otherButtonTitles:nil];
    [alert show];
    return YES;
}

@end
