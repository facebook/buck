/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBInteraction.h>

@protocol FBApplicationCommands;
@class FBApplicationLaunchConfiguration;

NS_ASSUME_NONNULL_BEGIN

/**
 Bridges Commands to Interactions.
 */
@interface FBCommandInteractions : NSObject

/**
 Installs application at given path on the host.

 @param path the file path of the Application Bundle on the host.
 @param command the command to use.
 @return an Interaction for further invocation.
 */
+ (id<FBInteraction>)installApplicationWithPath:(NSString *)path command:(id<FBApplicationCommands>)command;

/**
 Queries to see if an Application is installed on iOS.

 @param bundleID The Bundle ID of the application.
 @param command the command to use.
 @return an Interaction for further invocation.
 */
+ (id<FBInteraction>)isApplicationInstalledWithBundleID:(NSString *)bundleID command:(id<FBApplicationCommands>)command;

/**
 Launches an Application with the provided Application Launch Configuration.

 @param configuration the Application Launch Configuration to use.
 @return an Interaction for further invocation.
 */
+ (id<FBInteraction>)launchApplication:(FBApplicationLaunchConfiguration *)configuration command:(id<FBApplicationCommands>)command;

/**
 Kills application with given bundleID

 @param bundleID bundle ID of installed application
 @param command the command to use.
 @return an Interaction for further invocation.
 */
+ (id<FBInteraction>)killApplicationWithBundleID:(NSString *)bundleID command:(id<FBApplicationCommands>)command;

@end

NS_ASSUME_NONNULL_END
