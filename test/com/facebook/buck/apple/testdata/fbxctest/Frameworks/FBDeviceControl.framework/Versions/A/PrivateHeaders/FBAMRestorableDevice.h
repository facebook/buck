/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBControlCore.h>
#import <FBDeviceControl/FBAMDefines.h>
#import <FBDeviceControl/FBDeviceCommands.h>

NS_ASSUME_NONNULL_BEGIN

typedef NSString *FBDeviceKey NS_STRING_ENUM;
extern FBDeviceKey const FBDeviceKeyChipID;
extern FBDeviceKey const FBDeviceKeyDeviceClass;
extern FBDeviceKey const FBDeviceKeyDeviceName;
extern FBDeviceKey const FBDeviceKeyLocationID;
extern FBDeviceKey const FBDeviceKeyProductType;
extern FBDeviceKey const FBDeviceKeySerialNumber;
extern FBDeviceKey const FBDeviceKeyUniqueChipID;
extern FBDeviceKey const FBDeviceKeyUniqueDeviceID;
extern FBDeviceKey const FBDeviceKeyCPUArchitecture;
extern FBDeviceKey const FBDeviceKeyBuildVersion;
extern FBDeviceKey const FBDeviceKeyProductVersion;
extern FBDeviceKey const FBDeviceKeyActivationState;

/**
 An Object Wrapper around AMRestorableDevice
 */
@interface FBAMRestorableDevice : NSObject <FBiOSTargetInfo, FBDevice>

/**
 The Designated Initializer.

 @param calls the calls to use.
 @param restorableDevice the AMRestorableDeviceRef
 @param allValues the cached device values.
 @param logger the logger to use.
 @return a new instance.
 */
- (instancetype)initWithCalls:(AMDCalls)calls restorableDevice:(AMRestorableDeviceRef)restorableDevice allValues:(NSDictionary<NSString *, id> *)allValues logger:(id<FBControlCoreLogger>)logger;

/**
 The Restorable Device instance.
 */
@property (nonatomic, assign, readwrite) AMRestorableDeviceRef restorableDevice;

/**
 Cached Device Values.
 */
@property (nonatomic, copy, readwrite) NSDictionary<NSString *, id> *allValues;

/**
 Convert AMRestorableDeviceState to FBiOSTargetState.

 @param state the state integer.
 @return the FBiOSTargetState corresponding to the AMRestorableDeviceState
 */
+ (FBiOSTargetState)targetStateForDeviceState:(AMRestorableDeviceState)state;

@end

NS_ASSUME_NONNULL_END
