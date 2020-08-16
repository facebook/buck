/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** A BuildRule for preparation of Info.plist file to be put later into bundle */
public class AppleInfoPlist extends ModernBuildRule<AppleInfoPlist.Impl> {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-info-plist");

  public AppleInfoPlist(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath infoPlistPath,
      Optional<SourcePath> maybeAssetCatalogPlistSourcePath,
      boolean isLegacyWatchApp,
      Optional<String> maybeProductName,
      String extension,
      AppleCxxPlatform appleCxxPlatform,
      Map<String, String> substitutions,
      Optional<String> minimumOSVersion) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            buildTarget,
            infoPlistPath,
            maybeAssetCatalogPlistSourcePath,
            isLegacyWatchApp,
            getBinaryName(buildTarget, maybeProductName),
            extension,
            appleCxxPlatform,
            substitutions,
            minimumOSVersion));
  }

  private static String getBinaryName(BuildTarget buildTarget, Optional<String> maybeProductName) {
    return maybeProductName.orElse(buildTarget.getShortName());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final SourcePath sourcePath;
    @AddToRuleKey private final Optional<SourcePath> maybeAssetCatalogPlistSourcePath;
    @AddToRuleKey private final Boolean isLegacyWatchApp;
    @AddToRuleKey private final String binaryName;
    @AddToRuleKey private final String extension;
    @AddToRuleKey private final ApplePlatform platform;
    @AddToRuleKey private final String sdkName;
    @AddToRuleKey private final String sdkVersion;
    @AddToRuleKey private final String minOSVersion;
    @AddToRuleKey private final Optional<String> maybePlatformBuildVersion;
    @AddToRuleKey private final Optional<String> maybeXcodeBuildVersion;
    @AddToRuleKey private final Optional<String> maybeXcodeVersion;
    @AddToRuleKey private final ImmutableMap<String, String> substitutions;

    Impl(
        BuildTarget buildTarget,
        SourcePath sourcePath,
        Optional<SourcePath> maybeAssetCatalogPlistSourcePath,
        boolean isLegacyWatchApp,
        String binaryName,
        String extension,
        AppleCxxPlatform appleCxxPlatform,
        Map<String, String> substitutions,
        Optional<String> minimumOSVersion) {
      this.buildTarget = buildTarget;
      this.output = new OutputPath("Info.plist");
      this.sourcePath = sourcePath;
      this.maybeAssetCatalogPlistSourcePath = maybeAssetCatalogPlistSourcePath;
      this.isLegacyWatchApp = isLegacyWatchApp;
      this.binaryName = binaryName;
      this.extension = extension;
      AppleSdk sdk = appleCxxPlatform.getAppleSdk();
      this.platform = sdk.getApplePlatform();
      this.sdkName = sdk.getName();
      this.sdkVersion = sdk.getVersion();
      this.minOSVersion = minimumOSVersion.orElse(appleCxxPlatform.getMinVersion());
      this.maybePlatformBuildVersion = appleCxxPlatform.getBuildVersion();
      this.maybeXcodeBuildVersion = appleCxxPlatform.getXcodeBuildVersion();
      this.maybeXcodeVersion = appleCxxPlatform.getXcodeVersion();
      this.substitutions = ImmutableMap.copyOf(substitutions);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      Path tempInfoPlistPath = outputPathResolver.getTempPath("Info.plist");
      AbsPath infoPlistPath = buildContext.getSourcePathResolver().getAbsolutePath(sourcePath);

      Step sedStep =
          new FindAndReplaceStep(
              filesystem,
              infoPlistPath,
              tempInfoPlistPath,
              InfoPlistSubstitution.createVariableExpansionFunction(
                  InfoPlistSubstitution.variableExpansionWithDefaults(
                      substitutions,
                      ImmutableMap.of(
                          "EXECUTABLE_NAME", binaryName,
                          "PRODUCT_NAME", binaryName))));

      RelPath outputPath = outputPathResolver.resolvePath(output);
      Step processStep =
          new PlistProcessStep(
              filesystem,
              tempInfoPlistPath,
              maybeAssetCatalogPlistSourcePath.map(
                  sourcePath ->
                      buildContext
                          .getSourcePathResolver()
                          .getCellUnsafeRelPath(sourcePath)
                          .getPath()),
              outputPath.getPath(),
              getInfoPlistAdditionalKeys(),
              getInfoPlistOverrideKeys(),
              PlistProcessStep.OutputFormat.BINARY);

      return ImmutableList.of(sedStep, processStep);
    }

    private ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys() {
      ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

      switch (platform.getType()) {
        case MAC:
          if (needsAppInfoPlistKeysOnMac()) {
            keys.put("NSHighResolutionCapable", new NSNumber(true));
            keys.put("NSSupportsAutomaticGraphicsSwitching", new NSNumber(true));
          }
          keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("MacOSX")));
          break;
        case IOS_DEVICE:
          keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneOS")));
          break;
        case IOS_SIMULATOR:
          keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneSimulator")));
          break;
        case WATCH_DEVICE:
          if (!isLegacyWatchApp) {
            keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchOS")));
          }
          break;
        case WATCH_SIMULATOR:
          if (!isLegacyWatchApp) {
            keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("WatchSimulator")));
          }
          break;
        case TV_DEVICE:
        case TV_SIMULATOR:
        case UNKNOWN:
          break;
      }

      keys.put("DTPlatformName", new NSString(platform.getName()));
      keys.put("DTPlatformVersion", new NSString(sdkVersion));
      keys.put("DTSDKName", new NSString(sdkName + sdkVersion));
      keys.put("MinimumOSVersion", new NSString(minOSVersion));
      if (maybePlatformBuildVersion.isPresent()) {
        keys.put("DTPlatformBuild", new NSString(maybePlatformBuildVersion.get()));
        keys.put("DTSDKBuild", new NSString(maybePlatformBuildVersion.get()));
      }

      if (maybeXcodeBuildVersion.isPresent()) {
        keys.put("DTXcodeBuild", new NSString(maybeXcodeBuildVersion.get()));
      }

      if (maybeXcodeVersion.isPresent()) {
        keys.put("DTXcode", new NSString(maybeXcodeVersion.get()));
      }

      return keys.build();
    }

    private boolean needsAppInfoPlistKeysOnMac() {
      // XPC bundles on macOS don't require app-specific keys
      // (which also confuses Finder in displaying the XPC bundles as apps)
      return !extension.equals(AppleBundleExtension.XPC.fileExtension);
    }

    private ImmutableMap<String, NSObject> getInfoPlistOverrideKeys() {
      ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

      if (platform.getType() == ApplePlatformType.MAC) {
        if (needsLSRequiresIPhoneOSInfoPlistKeyOnMac()) {
          keys.put("LSRequiresIPhoneOS", new NSNumber(false));
        }
      } else if (!platform.getType().isWatch() && !isLegacyWatchApp) {
        keys.put("LSRequiresIPhoneOS", new NSNumber(true));
      }

      return keys.build();
    }

    private boolean needsLSRequiresIPhoneOSInfoPlistKeyOnMac() {
      return !extension.equals(AppleBundleExtension.XPC.fileExtension);
    }
  }
}
