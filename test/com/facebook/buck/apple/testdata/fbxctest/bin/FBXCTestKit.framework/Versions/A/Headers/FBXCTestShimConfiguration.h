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

NS_ASSUME_NONNULL_BEGIN

/**
 A Configuration object for the location of the Test Shims.
 */
@interface FBXCTestShimConfiguration : NSObject <FBJSONSerializable, NSCopying>


/**
 Constructs a Shim Configuration from the default base directory.

 @param error an error out for any of the shims that could not be located.
 @return a Shim Configuration on success, nil otherwise.
 */
+ (nullable instancetype)defaultShimConfigurationWithError:(NSError **)error;

/**
 Constructs a Shim Configuration from the given base directory.

 @param directory the base directory of the shims
 @param error an error out for any of the shims that could not be located.
 @return a Shim Configuration on success, nil otherwise.
 */
+ (nullable instancetype)shimConfigurationWithDirectory:(NSString *)directory error:(NSError **)error;

/**
 The Designated Intializer.

 @param iosSimulatorTestShim The Path to he iOS Simulator Test Shim.
 @param macTestShim The Path to the Mac Test Shim.
 @param macQueryShim The Path to the Mac Query Shim.
 */
- (instancetype)initWithiOSSimulatorTestShim:(NSString *)iosSimulatorTestShim macTestShim:(NSString *)macTestShim macQueryShim:(NSString *)macQueryShim;

#pragma mark Helpers

/**
 Determines the location of the shim directory, or fails

 @param error an error out for any of the shims that could not be located.
 @return a Path to the Shim Configuration.
 */
+ (nullable NSString *)findShimDirectoryWithError:(NSError **)error;


#pragma mark Properties

/**
 The location of the shim used to run iOS Simulator Logic Tests.
 */
@property (nonatomic, copy, readonly) NSString *iOSSimulatorOtestShimPath;

/**
 The location of the shim used to run Mac Logic Tests.
 */
@property (nonatomic, copy, readonly) NSString *macOtestShimPath;

/**
 The location of the shim used to query Mac Tests.
 */
@property (nonatomic, copy, readonly) NSString *macOtestQueryPath;

@end

NS_ASSUME_NONNULL_END
