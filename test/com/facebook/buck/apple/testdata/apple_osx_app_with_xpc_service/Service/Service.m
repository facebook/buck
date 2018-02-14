// Copyright 2004-present Facebook. All Rights Reserved.

#import <Foundation/Foundation.h>

@interface MyDelegateClass: NSObject <NSXPCListenerDelegate>
@end

@implementation MyDelegateClass
@end

int main(int argc, const char *argv[]) {
    MyDelegateClass *myDelegate = [MyDelegateClass new];
    NSXPCListener *listener =
        [NSXPCListener serviceListener];
 
    listener.delegate = myDelegate;
    [listener resume];
 
    exit(EXIT_FAILURE);
    return 0;
}
