/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

#import <CoreMedia/CoreMedia.h>
#import <Foundation/Foundation.h>

#import <FBControlCore/FBControlCore.h>

@class FBDiagnostic;
@protocol FBSimulatorScale;

/**
 Options for FBFramebufferVideo.
 */
typedef NS_OPTIONS(NSUInteger, FBFramebufferVideoOptions) {
  FBFramebufferVideoOptionsAutorecord = 1 << 0, /** If Set, will automatically start recording when the first video frame is recieved. **/
  FBFramebufferVideoOptionsImmediateFrameStart = 1 << 1, /** If Set, will start recording a video immediately, using the previously delivered frame **/
  FBFramebufferVideoOptionsFinalFrame = 1 << 2, /** If Set, will repeat the last frame just before a video is stopped **/
};

NS_ASSUME_NONNULL_BEGIN

/**
 A Configuration Value for a Framebuffer.
 */
@interface FBFramebufferConfiguration : NSObject <NSCoding, NSCopying, FBJSONSerializable, FBDebugDescribeable>

/**
 The Options for the Video Component.
 */
@property (nonatomic, assign, readonly) FBFramebufferVideoOptions videoOptions;

/**
 YES to show a debug window, NO otherwise.
 */
@property (nonatomic, assign, readonly) BOOL showDebugWindow;

/**
 The Timescale used in Video Encoding.
 */
@property (nonatomic, assign, readonly) CMTimeScale timescale;

/**
 The Rounding Method used for Video Frames.
 */
@property (nonatomic, assign, readonly) CMTimeRoundingMethod roundingMethod;

/**
 The Scale of the Framebuffer.
 */
@property (nonatomic, nullable, copy, readonly) id<FBSimulatorScale> scale;

/**
 The Diagnostic Value to determine the video path.
 */
@property (nonatomic, nullable, copy, readonly) FBDiagnostic *diagnostic;

/**
 The FileType of the Video.
 */
@property (nonatomic, nullable, copy, readonly) NSString *fileType;

#pragma mark Defaults & Initializers

/**
 The Default Value of FBFramebufferConfiguration.
 Uses Reasonable Defaults.
 */
+ (instancetype)defaultConfiguration;

/**
 The Default Value of FBFramebufferConfiguration.
 Use this in preference to 'defaultConfiguration' if video encoding is problematic.
 */
+ (instancetype)prudentConfiguration;

/**
 Creates and Returns a new FBFramebufferConfiguration Value with the provided parameters.

 @param diagnostic The Diagnostic Value to determine the video path
 @param scale the Scale of the Framebuffer.
 @param videoOptions The Flags for FBFramebufferVideo.
 @param timescale The Timescale used in Video Encoding.
 @param roundingMethod The Rounding Method used for Video Frames.
 @param fileType The FileType of the Video.
 @return a FBFramebufferConfiguration instance.
 */
+ (instancetype)withDiagnostic:(nullable FBDiagnostic *)diagnostic scale:(nullable id<FBSimulatorScale>)scale videoOptions:(FBFramebufferVideoOptions)videoOptions timescale:(CMTimeScale)timescale roundingMethod:(CMTimeRoundingMethod)roundingMethod fileType:(nullable NSString *)fileType;

#pragma mark Diagnostics

/**
 Returns a new Configuration with the Diagnostic Applied.
 */
+ (instancetype)withDiagnostic:(FBDiagnostic *)diagnostic;
- (instancetype)withDiagnostic:(FBDiagnostic *)diagnostic;

#pragma mark Options

/**
 Returns a new Configuration with the Options Applied.
 */
- (instancetype)withVideoOptions:(FBFramebufferVideoOptions)videoOptions;
+ (instancetype)withVideoOptions:(FBFramebufferVideoOptions)videoOptions;

#pragma mark Timescale

/**
 Returns a new Configuration with the Timescale Applied.
 */
- (instancetype)withTimescale:(CMTimeScale)timescale;
+ (instancetype)withTimescale:(CMTimeScale)timescale;

#pragma mark Rounding

/**
 Returns a new Configuration with the Rounding Method Applied.
 */
- (instancetype)withRoundingMethod:(CMTimeRoundingMethod)roundingMethod;
+ (instancetype)withRoundingMethod:(CMTimeRoundingMethod)roundingMethod;

#pragma mark File Type

/**
 Returns a new Configuration with the File Type Applied.
 */
- (instancetype)withFileType:(NSString *)fileType;
+ (instancetype)withFileType:(NSString *)fileType;

#pragma mark Scale

/**
 Returns a new Configuration with the Scale Applied.
 */
- (instancetype)withScale:(nullable id<FBSimulatorScale>)scale;
+ (instancetype)withScale:(nullable id<FBSimulatorScale>)scale;

/**
 Scales the provided size with the receiver's scale/

 @param size the size to scale.
 @return a scaled size.
 */
- (CGSize)scaleSize:(CGSize)size;

@end

NS_ASSUME_NONNULL_END
