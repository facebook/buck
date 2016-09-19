/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBProcessInfo;
@class FBSimulator;

NS_ASSUME_NONNULL_BEGIN

/**
 An Interface to a Simulator's launchctl.
 */
@interface FBSimulatorLaunchCtl : NSObject

#pragma mark Intializers

/**
 Creates a FBSimulatorLaunchCtl instance for the provided Simulator

 @param simulator the Simulator to create a launchctl wrapper for.
 @return a new FBSimulatorLaunchCtl instance.
 */
+ (instancetype)withSimulator:(FBSimulator *)simulator;

#pragma mark Querying Services

/**
 Finds the Service Name for a provided process.
 Will fail if there is no process matching the Process Info found.

 @param process the process to obtain the name for.
 @param error an error for any error that occurs.
 @return the Service Name of the Stopped process, or nil if the process does not exist.
 */
- (nullable NSString *)serviceNameForProcess:(FBProcessInfo *)process error:(NSError **)error;

/**
 Finds the Service Name for a given Application Bundle ID.
 Optionally provides the Process Identifier of this Application.

 @param bundleID the Bundle ID of the Application to fetch.
 @param processIdentifierOut an outparam for the pid of the Service. Will be set to -1 if there is no running process for the Service.
 @param error an error out for any error that occurs.
 @return a String for the Service Name, nil otherwise.
 */
- (nullable NSString *)serviceNameForBundleID:(NSString *)bundleID processIdentifierOut:(pid_t *)processIdentifierOut error:(NSError **)error;

/**
 Consults the Simulator's launchctl to determine if the given process

 @param process the process to look for.
 @param error an error for any error that occurs.
 @return YES if the Process is running, NO otherwise.
 */
- (BOOL)processIsRunningOnSimulator:(FBProcessInfo *)process error:(NSError **)error;

/**
 Returns the currently running launchctl services.
 Returns a Mapping of Service Name to Process Identifier.
 NSNull is used to represent services that do not have a Process Identifier.

 @param error an error out for any error that occurs.
 @return a Mapping of Service Name to Process identifier.
 */
- (nullable NSDictionary<NSString *, id> *)listServicesWithError:(NSError **)error;

#pragma mark Stopping Services

/**
 Stops the Provided Process, by Service Name.

 @param serviceName the name of the Process to Stop.
 @param error an error for any error that occurs.
 @return the Service Name of the Stopped process, or nil if the process does not exist.
 */
- (nullable NSString *)stopServiceWithName:(NSString *)serviceName error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
