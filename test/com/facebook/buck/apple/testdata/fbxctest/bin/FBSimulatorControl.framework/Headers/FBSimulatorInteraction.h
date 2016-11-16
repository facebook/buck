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
#import <FBSimulatorControl/FBSimulator.h>

@class FBApplicationDescriptor;
@class FBSimulator;
@class FBSimulatorConfiguration;

NS_ASSUME_NONNULL_BEGIN

/**
 Interactions for FBSimulator Instances.
 */
@interface FBSimulatorInteraction : FBInteraction

/**
 Returns a new Interaction for the provided Simulator.

 @param simulator the Simulator to interact with, must not be nil.
 */
+ (instancetype)withSimulator:(FBSimulator *)simulator;

@end

NS_ASSUME_NONNULL_END
