/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <XCTestBootstrap/FBTestManagerTestReporter.h>

NS_ASSUME_NONNULL_BEGIN

@class FBTestManagerTestReporterTestCaseFailure;

/**
 A Test Reporter Test Case.
 */
@interface FBTestManagerTestReporterTestCase : NSObject

/**
 Constructs a Test Reporter Test Case.

 @param testClass the test class name.
 @param method the test method name.
 @return a new test case instance.
 */
+ (instancetype)withTestClass:(NSString *)testClass method:(NSString *)method;

/**
 The test report status.
 */
@property (nonatomic, assign, readonly) FBTestReportStatus status;

/**
 The test case duration.
 */
@property (nonatomic, assign, readonly) NSTimeInterval duration;

/**
 A list of test case failures.
 */
@property (nonatomic, copy, readonly) NSArray<FBTestManagerTestReporterTestCaseFailure *> *failures;

/**
 The test method.
 */
@property (nonatomic, copy, readonly) NSString *method;

/**
 The test class.
 */
@property (nonatomic, copy, readonly) NSString *testClass;

/**
 Add a failure to the test case.

 @param failure the failure to add.
 */
- (void)addFailure:(FBTestManagerTestReporterTestCaseFailure *)failure;

/**
 Set the status and duration properties.

 @param status the status the test case finished with.
 @param duration the execution time of the test case.
 */
- (void)finishWithStatus:(FBTestReportStatus)status duration:(NSTimeInterval)duration;

@end

NS_ASSUME_NONNULL_END
