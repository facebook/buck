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
@class FBSimulatorConfiguration;
@class FBXCTestLogger;

@protocol FBControlCoreLogger;
@protocol FBXCTestReporter;

NS_ASSUME_NONNULL_BEGIN

/**
 The Configuration pased to FBXCTestRunner.
 */
@interface FBTestRunConfiguration : NSObject

/**
 Creates a configuration, passing dependencies. Is not usable until `loadWithArguments` is called.

 @param reporter a reporter to inject.
 @param environment environment additions for the process under test.
 @return a new test run configuration.
 */
- (instancetype)initWithReporter:(nullable id<FBXCTestReporter>)reporter processUnderTestEnvironment:(NSDictionary<NSString *, NSString *> *)environment;

@property (nonatomic, strong, readonly) FBXCTestLogger *logger;
@property (nonatomic, strong, readonly) id<FBXCTestReporter> reporter;
@property (nonatomic, strong, readonly) FBSimulatorConfiguration *targetDeviceConfiguration;

@property (nonatomic, copy, readonly) NSDictionary<NSString *, NSString *> *processUnderTestEnvironment;
@property (nonatomic, copy, readonly) NSString *workingDirectory;
@property (nonatomic, copy, readonly) NSString *testBundlePath;
@property (nonatomic, copy, readonly) NSString *runnerAppPath;
@property (nonatomic, copy, readonly) NSString *simulatorName;
@property (nonatomic, copy, readonly) NSString *simulatorOS;
@property (nonatomic, copy, readonly) NSString *testFilter;

@property (nonatomic, assign, readonly) BOOL runWithoutSimulator;
@property (nonatomic, assign, readonly) BOOL listTestsOnly;

@property (nonatomic, copy, nullable, readonly) NSString *shimDirectory;
@property (nonatomic, copy, nullable, readonly) NSString *iOSSimulatorOtestShimPath;
@property (nonatomic, copy, nullable, readonly) NSString *macOtestShimPath;
@property (nonatomic, copy, nullable, readonly) NSString *macOtestQueryPath;

/**
 Loads the Configuration, with the provided parameters.

 @param arguments the Arguments to the fbxctest process
 @param workingDirectory the Working Directory to use.
 @param error an error out for any error that occurs
 @return YES if succcessful, NO otherwise.
 */
- (BOOL)loadWithArguments:(NSArray<NSString *> *)arguments workingDirectory:(NSString *)workingDirectory error:(NSError **)error;

/**
 Locates the expected Installation Root.
 */
+ (nullable NSString *)fbxctestInstallationRoot;

/**
 Attempts to locate the shims that are used for querying and running logic tests.

 @param error an error out for any error that occurs.
 @return the shim directory if successful, NO otherwise.
 */
+ (nullable NSString *)findShimDirectoryWithError:(NSError **)error;

/**
 Gets the path to the xctest executable for the given simulator (or for a mac test).

 @param simulator the Simulator to get the path for, if a simulator test.
 @return the path to the xctest exectuable
 */
- (NSString *)xctestPathForSimulator:(nullable FBSimulator *)simulator;

/**
 Gets the Environment for a Subprocess.
 Will extract the environment variables from the appropriately prefixed environment variables.
 Will strip out environment variables that will confuse subprocesses if this class is called inside an 'xctest' environment.

 @param entries the entries to add in
 @param simulator the Simulator, if applicable.
 @return the subprocess environment
 */
+ (NSDictionary<NSString *, NSString *> *)buildEnvironmentWithEntries:(NSDictionary<NSString *, NSString *> *)entries simulator:(nullable FBSimulator *)simulator;

@end

NS_ASSUME_NONNULL_END
