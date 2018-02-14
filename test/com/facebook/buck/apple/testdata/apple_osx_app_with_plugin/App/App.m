#import <AppKit/AppKit.h>
#import "App.h"

@interface App () <NSApplicationDelegate>

@end

@implementation App

- (void)applicationWillFinishLaunching:(NSNotification *)notification {
  NSLog(@"Test host app launched, test should pass");
}

- (int)magicValue {
  return 42;
}

@end

int main(int argc, char **argv) {
  NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];
  App* app = [[[App alloc] init] autorelease];
  [[NSApplication sharedApplication] setDelegate:app];
  [NSApp run];
  [pool release];
}
