/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@protocol FBControlCoreLogger;

NS_ASSUME_NONNULL_BEGIN

/**
 A Base Framework loader, that will ensure that the current user can load Frameworks.
 */
@interface FBControlCoreFrameworkLoader : NSObject

/**
 Confirms that the current user can load Frameworks.
 Subclasses should load the frameworks upon which they depend.

 @param logger the Logger to log events to.
 @param error any error that occurred during performing the preconditions.
 @returns YES if FBSimulatorControl is usable, NO otherwise.
 */
+ (BOOL)loadPrivateFrameworks:(nullable id<FBControlCoreLogger>)logger error:(NSError **)error;

/**
 Calls +[FBControlCore loadPrivateFrameworks:error], aborting in the event the Frameworks could not be loaded
 */
+ (void)loadPrivateFrameworksOrAbort;

/**
 The Name of the Loading Framework.
 Subclasses should re-define this method.
 */
+ (NSString *)loadingFrameworkName;

@end

NS_ASSUME_NONNULL_END
