/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <FBSimulatorControl/FBSimulator.h>
#import <FBSimulatorControl/FBSimulatorEventSink.h>

@class FBMutableSimulatorEventSink;
@class FBSimulatorEventRelay;
@class FBSimulatorHistoryGenerator;
@class FBSimulatorProcessFetcher;
@class FBSimulatorResourceManager;
@protocol FBControlCoreLogger;

NS_ASSUME_NONNULL_BEGIN

@interface FBSimulator ()

@property (nonatomic, strong, readonly) FBSimulatorEventRelay *eventRelay;
@property (nonatomic, strong, readonly) FBSimulatorHistoryGenerator *historyGenerator;
@property (nonatomic, strong, readonly, nullable) FBMutableSimulatorEventSink *mutableSink;
@property (nonatomic, strong, readonly) FBSimulatorResourceManager *resourceSink;
@property (nonatomic, strong, readonly) FBSimulatorProcessFetcher *processFetcher;
@property (nonatomic, copy, readonly) NSArray *commandResponders;

@property (nonatomic, copy, readwrite) FBSimulatorConfiguration *configuration;
@property (nonatomic, weak, readwrite, nullable) FBSimulatorPool *pool;

+ (instancetype)fromSimDevice:(SimDevice *)device configuration:(nullable FBSimulatorConfiguration *)configuration launchdSimProcess:(nullable FBProcessInfo *)launchdSimProcess containerApplicationProcess:(nullable FBProcessInfo *)containerApplicationProcess set:(FBSimulatorSet *)set;
- (instancetype)initWithDevice:(SimDevice *)device configuration:(FBSimulatorConfiguration *)configuration set:(FBSimulatorSet *)set processFetcher:(FBSimulatorProcessFetcher *)processFetcher auxillaryDirectory:(NSString *)auxillaryDirectory logger:(nullable id<FBControlCoreLogger>)logger;

@end

NS_ASSUME_NONNULL_END
