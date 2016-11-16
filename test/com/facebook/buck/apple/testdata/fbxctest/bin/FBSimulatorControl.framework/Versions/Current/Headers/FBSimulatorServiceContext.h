/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBSimulatorControlConfiguration;
@class SimDeviceSet;
@class SimDeviceType;
@class SimRuntime;
@class SimServiceContext;

NS_ASSUME_NONNULL_BEGIN

/**
 An FBSimulatorControl wrapper for SimServiceContext.
 */
@interface FBSimulatorServiceContext : NSObject

/**
 Returns the Current SimSericeContext.
 */
+ (instancetype)sharedServiceContext;

/**
 Return the paths to all of the device sets.
 */
- (NSArray<NSString *> *)pathsOfAllDeviceSets;

/**
 Returns all of the supported runtimes.
 */
- (NSArray<SimRuntime *> *)supportedRuntimes;

/**
 Returns all of the supported device types.
 */
- (NSArray<SimDeviceType *> *)supportedDeviceTypes;

/**
 Obtains the SimDeviceSet for a given configuration.

 @param configuration the configuration to use.
 @param error an error out for any error that occurs.
 @return the Device Set if present. nil otherwise.
 */
- (nullable SimDeviceSet *)createDeviceSetWithConfiguration:(FBSimulatorControlConfiguration *)configuration error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
