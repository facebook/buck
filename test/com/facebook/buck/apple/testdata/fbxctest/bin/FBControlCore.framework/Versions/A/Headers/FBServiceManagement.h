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

/**
 An Interface to ServiceManagement.framework.
 */
@interface FBServiceManagement : NSObject

/**
 Returns the Job Information for a User launchd Service with the provided name.

 @param serviceName the Name of the Service to fetch.
 @return a Dictionary Representing the Service Information, if the Service is available.
 */
+ (nullable NSDictionary<NSString *, id> *)jobInformationForUserServiceNamed:(NSString *)serviceName;

/**
 Returns the Job Information for multiple services at once.

 @param serviceNames the Name of the Service to fetch.
 @return a Dictionary Representing the Service Information, if the Service is available.
 */
+ (NSDictionary<NSString *, NSDictionary<NSString *, id> *> *)jobInformationForUserServicesNamed:(NSArray<NSString *> *)serviceNames;

/**
 Returns the Job Information for all Services with a given substring in their launch path.

 @param launchPathSubstring the Launch Paths substring to search for.
 @return the Jobs Matching the Substring of the Launch Path.
 */
+ (NSArray<NSDictionary<NSString *, id> *> *)jobsWithProgramWithLaunchPathSubstring:(NSString *)launchPathSubstring;

@end

NS_ASSUME_NONNULL_END
