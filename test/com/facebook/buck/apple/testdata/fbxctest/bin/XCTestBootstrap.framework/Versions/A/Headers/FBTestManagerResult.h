/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBDiagnostic;
@class FBTestBundleResult;
@class FBTestDaemonResult;
@class XCTestBootstrapError;

NS_ASSUME_NONNULL_BEGIN

/**
 A Value Representing the end-result of a Test Run.
 The success or failure of a test run says nothing about the results of each individual test case.
 */
@interface FBTestManagerResult : NSObject

/**
 A Successful Test Run.

 @return a Successful Result.
 */
+ (instancetype)success;

/**
 A Result that indicates the client requested disconnection before the test manager had concluded.

 @return a Client Requested Disconnect Result.
 */
+ (instancetype)clientRequestedDisconnect;

/**
 A Test Run that Timed Out.

 @param timeout the Timeout in seconds.
 @return a Timeout Result
 */
+ (instancetype)timedOutAfter:(NSTimeInterval)timeout;

/**
 A Test Run in which the Bundle Connection Failed

 @param bundleResult the Bundle Result
 @return a Test Manger Result.
 */
+ (instancetype)bundleConnectionFailed:(FBTestBundleResult *)bundleResult;

/**
 A Test Run in which the Daemon Connection Failed

 @param daemonResult the Daemon Result
 @return a Test Manger Result.
 */
+ (instancetype)daemonConnectionFailed:(FBTestDaemonResult *)daemonResult;

/**
 A Test Run in which an internal error occured.

 @param error the internal error.
 @return an Internal Error Result.
 */
+ (instancetype)internalError:(XCTestBootstrapError *)error;

/**
 YES if the Test Manager finished successfully, NO otherwise.
 */
@property (nonatomic, assign, readonly) BOOL didEndSuccessfully;

/**
 The Underlying error, if an error occurred.
 */
@property (nonatomic, strong, nullable, readonly) NSError *error;

/**
 A Diagnostic for the Crash of a Test Host, if relevant.
 */
@property (nonatomic, strong, nullable, readonly) FBDiagnostic *crashDiagnostic;

@end

NS_ASSUME_NONNULL_END
