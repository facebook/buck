/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <XCTestBootstrap/FBTestManagerResultSummary.h>

@protocol FBXCTestReporter <NSObject>

- (void)didBeginExecutingTestPlan;
- (void)testSuite:(NSString *)testSuite didStartAt:(NSString *)startTime;
- (void)testCaseDidFinishForTestClass:(NSString *)testClass method:(NSString *)method withStatus:(FBTestReportStatus)status duration:(NSTimeInterval)duration;
- (void)testCaseDidFailForTestClass:(NSString *)testClass method:(NSString *)method withMessage:(NSString *)message file:(NSString *)file line:(NSUInteger)line;
- (void)testCaseDidStartForTestClass:(NSString *)testClass method:(NSString *)method;
- (void)finishedWithSummary:(FBTestManagerResultSummary *)summary;
- (void)didFinishExecutingTestPlan;

- (void)testHadOutput:(NSString *)output;

- (void)handleExternalEvent:(NSDictionary *)event;

- (BOOL)printReportWithError:(NSError **)error;

@end
