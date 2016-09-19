/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@class FBTestManagerResultSummary;
@class FBTestManagerTestReporterTestCase;

/**
 A Test Reporter Test Suite.
 */
@interface FBTestManagerTestReporterTestSuite : NSObject

/**
 Constructs a Test Reporter Test Suite.

 @param name the test suite name.
 @param startTime time when the test suite was started.
 @return a new test suite instance.
 */
+ (instancetype)withName:(NSString *)name startTime:(NSString *)startTime;

/**
 A list of test cases.
 */
@property (nonatomic, copy, readonly) NSArray<FBTestManagerTestReporterTestCase *> *testCases;

/**
 A list of nested test suites.
 */
@property (nonatomic, copy, readonly) NSArray<FBTestManagerTestReporterTestSuite *> *testSuites;

/**
 The test suite name.
 */
@property (nonatomic, copy, readonly) NSString *name;

/**
 The time when the test suite was started.
 */
@property (nonatomic, copy, readonly) NSString *startTime;

/**
 The test suite summary. May be nil.
 */
@property (nonatomic, readonly, nullable) FBTestManagerResultSummary *summary;

/**
 The parent test suite, if it is a nested test suite. May be nil.
 */
@property (nonatomic, weak, readonly, nullable) FBTestManagerTestReporterTestSuite *parent;

/**
 Add a test case to the test suite.

 @param testCase testCase to add.
 */
- (void)addTestCase:(FBTestManagerTestReporterTestCase *)testCase;

/**
 Add a nested test suite.

 @param testSuite testSuite to add.
 */
- (void)addTestSuite:(FBTestManagerTestReporterTestSuite *)testSuite;

/**
 Set the summary property.

 @param summary the summary the test suite finished with.
 */
- (void)finishWithSummary:(FBTestManagerResultSummary *)summary;

@end

NS_ASSUME_NONNULL_END
