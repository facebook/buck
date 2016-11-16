/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBControlCore.h>

@class FBApplicationLaunchConfiguration;
@protocol FBTestManagerTestReporter;

NS_ASSUME_NONNULL_BEGIN

/**
 A Value object with the information required to launch a XCTest.
 */
@interface FBTestLaunchConfiguration : NSObject <NSCopying, FBJSONSerializable, FBDebugDescribeable>

/**
 The Designated Initializer

 @param testBundlePath path to test bundle
 @return a new FBTestLaunchConfiguration Instance
 */
+ (instancetype)configurationWithTestBundlePath:(NSString *)testBundlePath;

/**
 Path to XCTest bundle used for testing
 */
@property (nonatomic, copy, readonly) NSString *testBundlePath;

/**
 Configuration used to launch test runner application.
 */
@property (nonatomic, copy, readonly, nullable) FBApplicationLaunchConfiguration *applicationLaunchConfiguration;

/**
 Path to host app.
 */
@property (nonatomic, copy, readonly, nullable) NSString *testHostPath;

/**
 Timeout for the Test Launch.
 */
@property (nonatomic, assign, readonly) NSTimeInterval timeout;

/**
 Determines whether should initialize for UITesting
 */
@property (nonatomic, assign, readonly) BOOL shouldInitializeUITesting;

/**
 Adds application launch configuration

 @param applicationLaunchConfiguration added application launch configuration
 @return new test launch configuration with changes applied.
 */
- (instancetype)withApplicationLaunchConfiguration:(FBApplicationLaunchConfiguration *)applicationLaunchConfiguration;

/**
 Adds timeout.

 @param timeout timeout
 @return new test launch configuration with changes applied.
 */
- (instancetype)withTimeout:(NSTimeInterval)timeout;

/**
 Adds test host path.

 @param testHostPath test host path
 @return new test launch configuration with changes applied.
 */
- (instancetype)withTestHostPath:(NSString *)testHostPath;

/**
 Determines whether should initialize for UITesting

 @param shouldInitializeUITesting sets whether should initialize UITesting when starting test
 @return new test launch configuration with changes applied.
 */
- (instancetype)withUITesting:(BOOL)shouldInitializeUITesting;

@end

NS_ASSUME_NONNULL_END
