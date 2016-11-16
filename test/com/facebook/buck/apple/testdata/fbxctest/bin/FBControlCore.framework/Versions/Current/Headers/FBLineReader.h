// Copyright 2004-present Facebook. All Rights Reserved.

#import <Foundation/Foundation.h>

#import <FBControlCore/FBMultiFileReader.h>

NS_ASSUME_NONNULL_BEGIN

/**
 A Reader of Text Data, calling the callback when a full line is available.
 */
@interface FBLineReader : NSObject <FBFileDataConsumer>

/**
 Creates a Consumer

 @param consumer the block to call when a line has been consumed.
 @return a new Line Reader.
 */
+ (instancetype)lineReaderWithConsumer:(void (^)(NSString *))consumer;

@end

NS_ASSUME_NONNULL_END
