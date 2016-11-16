// Copyright 2004-present Facebook. All Rights Reserved.

#import <Foundation/Foundation.h>

#import <FBControlCore/FBFileDataConsumer.h>

NS_ASSUME_NONNULL_BEGIN

/**
 A Multiplexer for File Reading.
 */
@interface FBMultiFileReader : NSObject

/**
 Adds a File Handle to be consumed.

 @param handle the Handle to Consume.
 @param consumer to block to consume with.
 @param error an error out for any error that occurs in registering consumers.
 @return YES if successful, NO otherwisse.
 */
- (BOOL)addFileHandle:(NSFileHandle *)handle withConsumer:(id<FBFileDataConsumer>)consumer error:(NSError **)error;

/**
 Reads from all file handeles, until the provided block returns.

 @param block the block to wait for completion with.
 @param error an error out for any error that occurs.
 @return YES if successful, NO otherwise.
 */
- (BOOL)readWhileBlockRuns:(void (^)())block error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
