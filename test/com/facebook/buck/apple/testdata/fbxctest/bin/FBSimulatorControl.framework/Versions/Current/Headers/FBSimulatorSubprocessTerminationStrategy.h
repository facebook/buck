/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

@class FBProcessInfo;
@class FBSimulator;

NS_ASSUME_NONNULL_BEGIN

/**
 A Strategy for Terminating the Suprocesses of a Simulator.
 */
@interface FBSimulatorSubprocessTerminationStrategy : NSObject

/**
 Creates and Returns a Strategy for Terminating the Subprocesses of a Simulator's 'launchd_sim'

 @param simulator the Simulator to Terminate Processes.
 */
+ (instancetype)forSimulator:(FBSimulator *)simulator;

/**
 Terminates a Process for a Simulator.
 Will fail if the Process does not belong to the Simulator.

 @param process the Process to terminate.
 @param error an error out for any error that occurs.
 @return YES if successful, NO otherwise.
 */
- (BOOL)terminate:(FBProcessInfo *)process error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
