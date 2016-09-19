/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBTestManagerContext;

@protocol FBDeviceOperator;
@protocol FBControlCoreLogger;
@protocol FBTestManagerTestReporter;

@class FBTestManagerResult;

NS_ASSUME_NONNULL_BEGIN

/**
 Manages a connection with the 'testmanagerd' daemon.
 */
@interface FBTestManager : NSObject

/**
 Creates and returns a test manager with given paramenters.

 @param context the Context of the Test Manager.
 @param deviceOperator a device operator used to handle device.
 @param reporter an optional reporter to report test progress to.
 @param logger the logger object to log events to, may be nil.
 @return Prepared FBTestManager
 */
+ (instancetype)testManagerWithContext:(FBTestManagerContext *)context operator:(id<FBDeviceOperator>)deviceOperator reporter:(id<FBTestManagerTestReporter>)reporter logger:(id<FBControlCoreLogger>)logger;

/**
 Connects to the 'testmanagerd' daemon and to the test bundle.

 @param timeout the amount of time to wait for the connection to be established.
 @return A TestManager Result if an early-error occured, nil otherwise.
 */
- (nullable FBTestManagerResult *)connectWithTimeout:(NSTimeInterval)timeout;

/**
 Waits until testing has finished.

 @param timeout the the maximum time to wait for test to finish.
 @return The TestManager Result.
 */
- (FBTestManagerResult *)waitUntilTestingHasFinishedWithTimeout:(NSTimeInterval)timeout;

/**
 Disconnects from the 'testmanagerd' daemon.

 @return The TestManager Result.
 */
- (FBTestManagerResult *)disconnect;

@end

NS_ASSUME_NONNULL_END
