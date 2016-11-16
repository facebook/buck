/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <Foundation/Foundation.h>

#import <FBControlCore/FBTask.h>

NS_ASSUME_NONNULL_BEGIN

@protocol FBControlCoreLogger;
@protocol FBFileDataConsumer;

/**
 An interface to building FBTask instances
 */
@interface FBTaskBuilder : NSObject

/**
 Creates a new Task Builder with the provided launch path.

 @param launchPath the launch path to use. Must not be nil.
 @return a new Task Builder.
 */
+ (instancetype)withLaunchPath:(NSString *)launchPath;

/**
 Creates a new Task Builder with the provided launch path.

 @param launchPath the launch path to use. Must not be nil.
 @param arguments the arguments to launch with.
 @return a new Task Builder.
 */
+ (instancetype)withLaunchPath:(NSString *)launchPath arguments:(NSArray<NSString *> *)arguments;

/**
 The Launch Path of the Task. Will override any shell command set with `shellCommand`.

 @param launchPath the Launch Path. Will remove shellCommand.
 @return the reciever, for chaining.
 */
- (instancetype)withLaunchPath:(NSString *)launchPath;

/**
 The Arguments of the Task. Will override any shell command set with `shellCommand`.

 @param arguments the arguments for the launch path.
 @return the reciever, for chaining.
 */
- (instancetype)withArguments:(NSArray<NSString *> *)arguments;

/**
 Adds the provided dictionary to the environment of the built task.

 @param environment an Environment Dictionary. Must not be nil.
 @return the reciever, for chaining.
 */
- (instancetype)withEnvironmentAdditions:(NSDictionary<NSString *, NSString *> *)environment;

/**
 Reads stdout into memory.

 @return the reciever, for chaining.
 */
- (instancetype)withStdOutInMemory;

/**
 Reads stderr into memory.

 @return the reciever, for chaining.
 */
- (instancetype)withStdErrInMemory;

/**
 Assigns a path to write stdout to.

 @param stdOutPath the path to write stdout to. Must not be nil.
 @return the reciever, for chaining.
 */
- (instancetype)withStdOutPath:(NSString *)stdOutPath;

/**
 Assigns a path to write stderr to.

 @param stdErrPath the path to write stderr to. Must not be nil.
 @return the reciever, for chaining.
 */
- (instancetype)withStdErrPath:(NSString *)stdErrPath;

/**
 Redirects stdout to /dev/null

 @return the reciever, for chaining.
 */
- (instancetype)withStdOutToDevNull;

/**
 Redirects stderr to /dev/null

 @return the reciever, for chaining.
 */
- (instancetype)withStdErrToDevNull;

/**
 Redirects stdout data to the consumer.

 @param consumer the consumer to consume the data.
 @return the reciever, for chaining.
 */
- (instancetype)withStdOutConsumer:(id<FBFileDataConsumer>)consumer;

/**
 Redirects stderr data to the consumer.

 @param consumer the consumer to consume the data.
 @return the reciever, for chaining.
 */
- (instancetype)withStdErrConsumer:(id<FBFileDataConsumer>)consumer;

/**
 Redirects stdout to the reader block, on a per line basis.

 @param reader the block to use for reading lines
 @return the reciever, for chaining.
 */
- (instancetype)withStdOutLineReader:(void (^)(NSString *))reader;

/**
 Redirects stderr to the reader block, on a per line basis.

 @param reader the block to use for reading lines
 @return the reciever, for chaining.
 */
- (instancetype)withStdErrLineReader:(void (^)(NSString *))reader;

/**
 Redirects stdout to the provided logger, on a per line basis.

 @param logger the logger to use for logging lines.
 @return the reciver, for chaining.
 */
- (instancetype)withStdOutToLogger:(id<FBControlCoreLogger>)logger;

/**
 Redirects stderr to the provided logger, on a per line basis.

 @param logger the logger to use for logging lines.
 @return the reciver, for chaining.
 */
- (instancetype)withStdErrToLogger:(id<FBControlCoreLogger>)logger;

/**
 The Set of Return Codes that are considered non-erroneous.

 @param statusCodes the non-erroneous stats codes.
 @return the reciever, for chaining.
 */
- (instancetype)withAcceptableTerminationStatusCodes:(NSSet<NSNumber *> *)statusCodes;

/**
 Builds the Task

 @return a FBTask.
 */
- (FBTask *)build;

/**
 Build a Task Configuration.

 @return a Task Configuration.
 */
- (FBTaskConfiguration *)buildConfiguration;

@end

/**
 Convenices on top of FBTaskBuilder
 */
@interface FBTaskBuilder (Convenience)

/**
 Creates a Task for execution.
 When the task is launched it will be retained until the task has terminated.
 Terminate must be called to free up resources.

 @param launchPath the Executable Path to launch.
 @param arguments the arguments to send to the launched tasks.
 @return a Task ready to be started.
 */
+ (FBTask *)taskWithLaunchPath:(NSString *)launchPath arguments:(NSArray<NSString *> *)arguments;

/**
 Executes the receiver.

 @param error an error out for any error that occurs.
 @return The stdout of the process if successful, nil otherwise.
 */
- (nullable NSString *)executeReturningError:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
