/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBBundleDescriptor.h>

NS_ASSUME_NONNULL_BEGIN

@class FBBinaryDescriptor;

/**
 A Bundle Descriptor specialized to Applications
 */
@interface FBApplicationDescriptor : FBBundleDescriptor

/**
 Constructs a FBApplicationDescriptor for the Application at the given path.

 @param path the path of the applocation to construct.
 @param error an error out.
 @returns a FBApplicationDescriptor instance if one could be constructed, nil otherwise.
 */
+ (nullable instancetype)applicationWithPath:(NSString *)path error:(NSError **)error;

/**
 Returns the FBApplicationDescriptor for the current version of Xcode's Simulator.app.
 Will assert if the FBApplicationDescriptor instance could not be constructed.

 @return A FBApplicationDescriptor instance for the Simulator.app.
 */
+ (instancetype)xcodeSimulator;

/**
 Returns the System Application with the provided name.

 @param appName the System Application to fetch.
 @param error any error that occurred in fetching the application.
 @returns FBApplicationDescriptor instance if one could for the given name could be found, nil otherwise.
 */
+ (nullable instancetype)systemApplicationNamed:(NSString *)appName error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
