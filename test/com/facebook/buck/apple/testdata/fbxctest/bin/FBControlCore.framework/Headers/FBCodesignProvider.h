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
 A Protocol for providing a codesigning implementation.
 */
@protocol FBCodesignProvider <NSObject>

/**
 Requests that the reciver codesigns a bundle.
 Implementors can provide an error if one occurs.

 @param bundlePath path to bundle that should be signed.
 @param error an error out for any error that occurs.
 @return YES if operation was successful
 */
- (BOOL)signBundleAtPath:(NSString *)bundlePath error:(NSError **)error;

/**
 Attempts to fetch the CDHash of a bundle.
 Implementors can provide an error if one occurs.

 @param bundlePath the file path to the bundle.
 @param error an error out for any error that occurs.
 @return YES if operation was successful
 */
- (nullable NSString *)cdHashForBundleAtPath:(NSString *)bundlePath error:(NSError **)error;

@end

/**
 A Default implementation of a Codesign Provider.
 */
@interface FBCodesignProvider : NSObject <FBCodesignProvider>

/**
 Identity used to codesign bundle.
 */
@property (nonatomic, copy, readonly) NSString *identityName;

/**
 @param identityName identity used to codesign bundle
 @return code sign command that signs bundles with given identity
 */
+ (instancetype)codeSignCommandWithIdentityName:(NSString *)identityName;

/**
 @return code sign command that signs bundles with the ad hoc identity.
 */
+ (instancetype)codeSignCommandWithAdHocIdentity;

@end

NS_ASSUME_NONNULL_END
