/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBJSONConversion.h>
#import <FBControlCore/FBDebugDescribeable.h>
#import <FBControlCore/FBApplicationCommands.h>

@class FBProcessInfo;
@protocol FBControlCoreConfiguration_Device;
@protocol FBControlCoreConfiguration_OS;

/**
 Uses the known values of SimDevice State, to construct an enumeration.
 These mirror the values from -[SimDeviceState state].
 */
typedef NS_ENUM(NSUInteger, FBSimulatorState) {
  FBSimulatorStateCreating = 0,
  FBSimulatorStateShutdown = 1,
  FBSimulatorStateBooting = 2,
  FBSimulatorStateBooted = 3,
  FBSimulatorStateShuttingDown = 4,
  FBSimulatorStateUnknown = 99,
};

/**
 Represents the kind of a target, device or simulator.
 */
typedef NS_OPTIONS(NSUInteger, FBiOSTargetType) {
  FBiOSTargetTypeNone = 0,
  FBiOSTargetTypeSimulator = 1 << 0,
  FBiOSTargetTypeDevice = 1 << 1,
  FBiOSTargetTypeAll = FBiOSTargetTypeSimulator | FBiOSTargetTypeDevice,
};

NS_ASSUME_NONNULL_BEGIN

/**
 Common Properties of Devices & Simulators.
 */
@protocol FBiOSTarget <NSObject, FBJSONSerializable, FBDebugDescribeable, FBApplicationCommands>

/**
 The Unique Device Identifier of the iOS Target.
 */
@property (nonatomic, copy, readonly) NSString *udid;

/**
 The Name of the iOS Target. This is the name given by the user, such as "Ada's iPhone"
 */
@property (nonatomic, copy, readonly) NSString *name;

/**
 The State of the iOS Target. Currently only applies to Simulators.
 */
@property (nonatomic, assign, readonly) FBSimulatorState state;

/**
 The Type of the iOS Target
 */
@property (nonatomic, assign, readonly) FBiOSTargetType targetType;

/**
 Process Information about the launchd process of the iOS Target. Currently only applies to Simulators.
 */
@property (nonatomic, copy, nullable, readonly) FBProcessInfo *launchdProcess;

/**
 Process Information about the Container Application of the iOS Target. Currently only applies to Simulators.
 */
@property (nonatomic, copy, nullable, readonly) FBProcessInfo *containerApplication;

/**
 The Configuration of the iOS Target's Device.
 */
@property (nonatomic, copy, readonly) id<FBControlCoreConfiguration_Device> deviceConfiguration;

/**
 The Configuration of the iOS Target's OS.
 */
@property (nonatomic, copy, readonly) id<FBControlCoreConfiguration_OS> osConfiguration;

/**
 A Comparison Method for `sortedArrayUsingSelector:`

 @param target the target to compare to.
 @return a Comparison Result.
 */
- (NSComparisonResult)compare:(id<FBiOSTarget>)target;

@end

/**
 The canonical string representation of the state enum.
 */
extern NSString *FBSimulatorStateStringFromState(FBSimulatorState state);

/**
 The canonical enum representation of the state string.
 */
extern FBSimulatorState FBSimulatorStateFromStateString(NSString *stateString);

/**
 The canonical string representations of the target type Option Set.
 */
NSArray<NSString *> *FBiOSTargetTypeStringsFromTargetType(FBiOSTargetType targetType);

/**
 The canonical enum representation of the state string.
 */
extern FBiOSTargetType FBiOSTargetTypeFromTargetTypeStrings(NSArray<NSString *> *targetTypeStrings);

/**
 A Default Comparison Function that can be called for different implementations of FBiOSTarget.
 */
extern NSComparisonResult FBiOSTargetComparison(id<FBiOSTarget> left, id<FBiOSTarget> right);

NS_ASSUME_NONNULL_END
