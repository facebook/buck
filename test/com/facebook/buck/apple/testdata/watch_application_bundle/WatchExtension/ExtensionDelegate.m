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


#import "ExtensionDelegate.h"

#import <WatchConnectivity/WatchConnectivity.h>

#import "WatchExtensionManager.h"

@interface ExtensionDelegate()<WCSessionDelegate>

@end


@implementation ExtensionDelegate

- (void)applicationDidFinishLaunching {
  [[WatchExtensionManager sharedInstance] appDidStart];
    // Perform any final initialization of your application.

  if ([WCSession isSupported]) {
    WCSession *session  = [WCSession defaultSession];
    session.delegate = self;
    [session activateSession];
  }

}

- (void)session:(nonnull WCSession *)session didReceiveMessage:(nonnull NSDictionary<NSString *,id> *)message
{
  NSLog(@"Did receive message %@", message);
}

@end
