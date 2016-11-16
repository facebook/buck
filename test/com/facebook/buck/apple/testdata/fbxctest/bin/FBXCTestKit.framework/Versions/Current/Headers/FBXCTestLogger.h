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
 A logger for FBXCTest that accumilates messages, but can be used for logging in the event a failure occurs.
 */
@interface FBXCTestLogger : NSObject<FBControlCoreLogger>

/**
 A Test Logger that will write to a default directory.

 @return a new FBXCTestLogger Instance
 */
+ (instancetype)defaultLoggerInDefaultDirectory;

/**
 A Test Logger that will write to a default directory.

 @param name a unique name for the logger.
 @return a new FBXCTestLogger Instance
 */
+ (instancetype)loggerInDefaultDirectory:(NSString *)name;

/**
 Returns the last n lines of logger output, for debugging purposes.

 @param lineCount the number of lines to output.
 @return the output, if it could be fetched.
 */
- (nullable NSString *)lastLinesOfOutput:(NSUInteger)lineCount;

/**
 Returns all the lines of logger output, for debugging purposes.

 @return the output, if it could be fetched.
 */
- (nullable NSString *)allLinesOfOutput;

@end

NS_ASSUME_NONNULL_END
