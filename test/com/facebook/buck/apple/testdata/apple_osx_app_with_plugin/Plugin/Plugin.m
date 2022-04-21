// Copyright (c) Meta Platforms, Inc. and affiliates. All Rights Reserved.

#import <Foundation/Foundation.h>

@interface PluginClass: NSObject
@end

@implementation PluginClass
@end

int main(int argc, const char *argv[]) {
    PluginClass *instance = [PluginClass new];
    exit(EXIT_FAILURE);
    return 0;
}
