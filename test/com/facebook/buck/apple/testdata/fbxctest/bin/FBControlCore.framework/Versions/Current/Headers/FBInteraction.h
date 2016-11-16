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
 Represents a Synchronous Action that can Succeed or Fail.
 */
@protocol FBInteraction <NSObject>

/**
 Perform the given interaction.

 @param error an errorOut if any ocurred.
 @returns YES if the interaction succeeded, NO otherwise.
 */
- (BOOL)perform:(NSError **)error;

@end

/**
 A Concrete FBInteraction that can be subclassed to provide a chainable API.
 */
@interface FBInteraction : NSObject <FBInteraction, NSCopying>

#pragma mark Primitives

/**
 Chains an interaction using the provided block

 @param block the block to perform the interaction with. Passes an NSError to return error information and the interaction for further chaining.
 @return the reciever, for chaining.
 */
+ (id<FBInteraction>)interact:(BOOL (^)(NSError **error))block;

/**
 Creates an Interaction that allways Fails.

 @param error the error to fail the interaction with.
 @return an Interaction that allways Fails.
 */
+ (id<FBInteraction>)fail:(NSError *)error;

/**
 Creates an Interaction that always Succeeds.

 @return an Interaction that always Succeeds.
 */
+ (id<FBInteraction>)succeed;

/**
 Creates an Interaction that will retry a base interaction a number of times, before failing.

 @param retries the number of retries, must be 1 or greater.
 @param interaction the base interaction to retry.
 @return a retrying interaction.
 */
+ (id<FBInteraction>)retry:(NSUInteger)retries interaction:(id<FBInteraction>)interaction;

/**
 Ignores any failure that occurs to the base interaction.

 @param interaction the interaction to attempt.
 @return an interaction that allways succeds.
 */
+ (id<FBInteraction>)ignoreFailure:(id<FBInteraction>)interaction;

/**
 Takes an NSArray<id<FBInteraction>> and returns an id<FBInteracton>.
 Any failing interaction will terminate the chain.

 @param interactions the interactions to chain together.
 */
+ (id<FBInteraction>)sequence:(NSArray<id<FBInteraction>> *)interactions;

/**
 Joins to interactions together.
 Equivalent to [FBInteraction sequence:@[first, second]]

 @param first the interaction to perform first.
 @param second the interaction to perform second.
 @return a chained interaction.
 */
+ (id<FBInteraction>)first:(id<FBInteraction>)first second:(id<FBInteraction>)second;

#pragma mark Initializer

/**
 Creates a Subclassable Interaction

 @param interaction the underlying interaction.
 @return a subclassable FBInteraction Instance.
 */
- (instancetype)initWithInteraction:(id<FBInteraction>)interaction;

#pragma mark Properties

/**
 The Base Interaction.
 */
@property (nonatomic, strong, readonly) id<FBInteraction> interaction;

#pragma mark Chaining

/**
 Chains and interaction using the provided interaction.

 @param next the interaction to chain.
 @return the reciever, for chaining.
 */
- (instancetype)chainNext:(id<FBInteraction>)next;

/**
 Chains an interaction using the provided block.

 @param block the block to perform the interaction with. Passes an NSError to return error information and the Interaction Subclass for further chaining.
 @return the reciever, for chaining.
 */
- (instancetype)interact:(BOOL (^)(NSError **error, id interaction))block;

/**
 Chains an interaction that will allways succeed.

 @return the reciever, for chaining.
 */
- (instancetype)succeed;

/**
 Chains an interaction that will allways fail

 @return the reciever, for chaining.
 */
- (instancetype)fail:(NSError *)error;

@end

NS_ASSUME_NONNULL_END
