/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBSimulator;
@class FBSimulator;
@class FBTestLaunchConfiguration;
@class FBTestManagerResult;
@protocol FBTestManagerTestReporter;

NS_ASSUME_NONNULL_BEGIN

/**
 A Strategy for Running Tests on a booted Simulator.
 */
@interface FBSimulatorTestRunStrategy : NSObject

/**
 Creates and returns a new Test Run Strategy.

@param simulator the Simulator to use.
 @param configuration the configuration to use.
 @param reporter the reporter to use.
 @param workingDirectory a directory which can be used for storage of temporary files.
 @return a new Test Run Strategy instance.
 */
+ (instancetype)strategyWithSimulator:(FBSimulator *)simulator configuration:(nullable FBTestLaunchConfiguration *)configuration  workingDirectory:(nullable NSString *)workingDirectory reporter:(nullable id<FBTestManagerTestReporter>)reporter;

/**
 Starts the Connection to the Test Host.

 @param error an error out for any error that occurs.
 @return the reciver for chaining. Will be nil if an error occurs.s
 */
- (nullable instancetype)connectAndStartWithError:(NSError **)error;

/**
 Starts the Connection to the Test Host.

 @param timeout a Timeout to wait for the the tests to complete in.
 @return a Test Manager Result.
 */
- (FBTestManagerResult *)waitUntilAllTestRunnersHaveFinishedTestingWithTimeout:(NSTimeInterval)timeout;

@end

NS_ASSUME_NONNULL_END
