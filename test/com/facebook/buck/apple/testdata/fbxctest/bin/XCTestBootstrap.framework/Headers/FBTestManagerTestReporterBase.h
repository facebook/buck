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

@class FBTestManagerTestReporterTestSuite;

/**
 A Base Test Reporter that implements the FBTestManagerTestReporter interface.
 It collects the Test Results of all Test Cases from all Test Suites and stores
 them in some objects/properties. It's a good starting point for implementing
 other Test Reporters by inheriting from this class.
 */
@interface FBTestManagerTestReporterBase : NSObject <FBTestManagerTestReporter>

/**
 The root test suite.
 */
@property (nonatomic, readonly) FBTestManagerTestReporterTestSuite *testSuite;

@end

NS_ASSUME_NONNULL_END
