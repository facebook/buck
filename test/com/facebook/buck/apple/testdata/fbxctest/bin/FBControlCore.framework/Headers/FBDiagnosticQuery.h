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

@class FBSimulatorDiagnostics;

/**
 A value object for describing which diagnostics to fetch.
 */
@interface FBDiagnosticQuery : NSObject <NSCopying, NSCoding, FBJSONSerializable, FBJSONDeserializable, FBDebugDescribeable>

#pragma mark Initializers

/**
 A Query for all diagnostics that match a given name.

 @param names the names to search for.
 @return a FBDiagnosticQuery.
 */
+ (instancetype)named:(NSArray<NSString *> *)names;

/**
 A Query for all static diagnostics.

 @return a FBDiagnosticQuery.
 */
+ (instancetype)all;

/**
 A Query for Diagnostics in an Application's Sandbox.

 @param bundleID the Application Bundle ID to search in.
 @param filenames the filenames to search for.
 @return a FBDiagnosticQuery.
 */
+ (instancetype)filesInApplicationOfBundleID:(NSString *)bundleID withFilenames:(NSArray<NSString *> *)filenames;

/**
 A Query for Crashes of a Process Type, after a date.

 @param processType the Process Types to search for.
 @param date the date to search from.
 @return a FBDiagnosticQuery.
 */
+ (instancetype)crashesOfType:(FBCrashLogInfoProcessType)processType since:(NSDate *)date;

@end

@interface FBDiagnosticQuery_All : FBDiagnosticQuery

@end

@interface FBDiagnosticQuery_Named : FBDiagnosticQuery

@property (nonatomic, copy, readonly, nonnull) NSArray<NSString *> *names;

@end

@interface FBDiagnosticQuery_ApplicationLogs : FBDiagnosticQuery

@property (nonatomic, copy, readonly, nonnull) NSString *bundleID;
@property (nonatomic, copy, readonly, nonnull) NSArray<NSString *> *filenames;

@end

@interface FBDiagnosticQuery_Crashes : FBDiagnosticQuery

@property (nonatomic, assign, readonly) FBCrashLogInfoProcessType processType;
@property (nonatomic, copy, readonly, nonnull) NSDate *date;

@end

NS_ASSUME_NONNULL_END
