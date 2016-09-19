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
@class FBTestManagerResult;

@protocol FBTestManagerProcessInteractionDelegate;
@protocol FBTestManagerTestReporter;
@protocol FBControlCoreLogger;
@protocol FBDeviceOperator;

extern const NSInteger FBProtocolVersion;
extern const NSInteger FBProtocolMinimumVersion;

NS_ASSUME_NONNULL_BEGIN

/**
 This is a simplified re-implementation of Apple's _IDETestManagerAPIMediator class.
 This class 'takes over' after an Application Process has been started.
 The class mediates between:
 - The Host
 - The 'testmanagerd' daemon running on iOS.
 - The 'Test Runner', the Appication in which the XCTest bundle is running.
 */
@interface FBTestManagerAPIMediator : NSObject

/**
 Delegate object used to handle application install & launch request
 */
@property (nonatomic, weak, readonly) id<FBTestManagerProcessInteractionDelegate> processDelegate;

/**
 Logger object to log events to, may be nil.
 */
@property (nonatomic, nullable, strong, readonly) id<FBControlCoreLogger> logger;

/**
 Creates and returns a mediator with given paramenters

 @param context the Context of the Test Manager.
 @param deviceOperator a device operator for device that test runner is running on
 @param processDelegate the Delegate to handle application interactivity.
 @param reporter the (optional) delegate to report test progress too.
 @param logger the (optional) logger to events to.
 @return Prepared FBTestRunnerConfiguration
 */
+ (instancetype)mediatorWithContext:(FBTestManagerContext *)context deviceOperator:(id<FBDeviceOperator>)deviceOperator processDelegate:(id<FBTestManagerProcessInteractionDelegate>)processDelegate reporter:(id<FBTestManagerTestReporter>)reporter logger:(id<FBControlCoreLogger>)logger;

/**
 Establishes a connection between the host, testmanagerd and the Test Bundle.
 This connection is established synchronously, until a timeout occurs.

 @param timeout a maximum time to wait for the connection to be established.
 @return A TestManager Result if an early-error occured, nil otherwise.
 */
- (nullable FBTestManagerResult *)connectToTestManagerDaemonAndBundleWithTimeout:(NSTimeInterval)timeout;

/**
 Executes the Test Plan over the established connection.
 This should be called after `-[FBTestManagerAPIMediator connectToTestManagerDaemonAndBundleWithTimeout:]`
 has successfully completed.
 Events will be delivered to the reporter asynchronously.

 @param timeout a maximum time to wait for the connection to be established.
 @return A TestManager Result if an early-error occured, nil otherwise.
 */
- (nullable FBTestManagerResult *)executeTestPlanWithTimeout:(NSTimeInterval)timeout;

/**
 Connecting mediator does not wait till test execution has finished.
 This method can be used in order to wait till test execution has finished.

 @param timeout the the maximum time to wait for tests to finish.
 @return A TestManager Result.
 */
- (FBTestManagerResult *)waitUntilTestRunnerAndTestManagerDaemonHaveFinishedExecutionWithTimeout:(NSTimeInterval)timeout;

/**
 Terminates connection between test runner(XCTest bundle) and testmanagerd.

 @return the TestManager Result.
 */
- (FBTestManagerResult *)disconnectTestRunnerAndTestManagerDaemon;

@end

NS_ASSUME_NONNULL_END
