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

NS_ASSUME_NONNULL_BEGIN

@class FBBinaryDescriptor;
@protocol FBFileManager;

/**
 Concrete value wrapper around a Application artifact.
 */
@interface FBBundleDescriptor : NSObject <NSCopying, NSCoding, FBJSONSerializable, FBDebugDescribeable>

#pragma mark Initializers

/**
 The Designated Initializer.

 @param name the Name of the Application. See CFBundleName. Must not be nil.
 @param path The Path to the Application Bundle. Must not be nil.
 @param bundleID the Bundle ID of the Application. Must not be nil.
 @param binary the Path to the binary inside the Application. Must not be nil.
 @returns a new FBBundleDescriptor instance.
 */
- (instancetype)initWithName:(NSString *)name path:(NSString *)path bundleID:(NSString *)bundleID binary:(FBBinaryDescriptor *)binary;

/**
 An initializer for FBBundleDescriptor that checks the nullability of the arguments

 @param name the Name of the Application. See CFBundleName. Must not be nil.
 @param path The Path to the Application Bundle. May be nil.
 @param bundleID the Bundle ID of the Application. May be nil.
 @param binary the Path to the binary inside the Application. May be nil.
 @returns a new FBBundleDescriptor instance, if all arguments are non-nil. Nil otherwise
 */
+ (nullable instancetype)withName:(NSString *)name path:(NSString *)path bundleID:(NSString *)bundleID binary:(FBBinaryDescriptor *)binary;

#pragma mark Public Methods

/**
 Relocates the reciever into a destination directory.

 @param destinationDirectory the Destination Path to relocate to. Must not be nil.
 @param fileManager the fileManager to use. Must not be nil.
 @param error an error out for any error that occurs.
 */
- (nullable instancetype)relocateBundleIntoDirectory:(NSString *)destinationDirectory fileManager:(id<FBFileManager>)fileManager error:(NSError **)error;


#pragma mark Properties

/**
 The name of the Application. See CFBundleName.
 */
@property (nonatomic, copy, readonly) NSString *name;

/**
 The File Path to the Application.
 */
@property (nonatomic, copy, readonly) NSString *path;

/**
 The Bundle Identifier of the Application. See CFBundleIdentifier.
 */
@property (nonatomic, copy, readonly) NSString *bundleID;

/**
 The Executable Binary contained within the Application's Bundle.
 */
@property (nonatomic, copy, readonly) FBBinaryDescriptor *binary;

@end

NS_ASSUME_NONNULL_END
