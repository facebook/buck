/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */


#import "ExampleAppDelegate.h"

#import <WatchConnectivity/WatchConnectivity.h>

#import "ExampleLoginViewController.h"

@interface ExampleAppDelegate()<WCSessionDelegate>

@end

@implementation ExampleAppDelegate
{
  NSInteger _counter;
}

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  self.window = [[UIWindow alloc] initWithFrame:[[UIScreen mainScreen] bounds]];
  self.window.rootViewController = [[ExampleLoginViewController alloc] init];
  [self.window makeKeyAndVisible];


  if ([WCSession isSupported]) {
    WCSession *session  = [WCSession defaultSession];
    session.delegate = self;
    [session activateSession];
    [self _sendCounter];
  }

  return YES;
}

- (void)_sendCounter
{
  [self _doSendCounter];
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    [self _sendCounter];
  });
}

- (void)_doSendCounter
{
  [[WCSession defaultSession] sendMessage:@{
                                            @"counter" : @(_counter)
                                            }
                             replyHandler:nil
                             errorHandler:^(NSError *__nonnull error) {
                               NSLog(@"Failed to send with error %@", error);
                             }];
}

- (void)applicationDidBecomeActive:(UIApplication *)application
{
}

@end
