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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.file.CopyFile;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class BuiltinApplePackage extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private final RelPath pathToOutputFile;
  private final RelPath temp;
  private final BuildRule bundle;
  private final ZipCompressionLevel compressionLevel;

  public BuiltinApplePackage(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRule bundle,
      ZipCompressionLevel compressionLevel) {
    super(buildTarget, projectFilesystem, params);
    // TODO(markwang): This will be different for Mac apps.
    this.pathToOutputFile =
        BuildTargetPaths.getGenPath(getProjectFilesystem().getBuckPaths(), buildTarget, "%s.ipa");
    this.temp = BuildTargetPaths.getScratchPath(getProjectFilesystem(), buildTarget, "__temp__%s");
    this.bundle = bundle;
    this.compressionLevel = compressionLevel;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    // Remove the output .ipa file if it exists already
    commands.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToOutputFile)));

    // Create temp folder to store the files going to be zipped

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), temp)));

    RelPath payloadDir = temp.resolveRel("Payload");
    commands.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), payloadDir)));

    // Recursively copy the .app directory into the Payload folder
    RelPath bundleOutputPath =
        context
            .getSourcePathResolver()
            .getCellUnsafeRelPath(Objects.requireNonNull(bundle.getSourcePathToOutput()));

    appendAdditionalAppleWatchSteps(context, commands);

    commands.add(
        CopyStep.forDirectory(
            bundleOutputPath, payloadDir, CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));

    appendAdditionalSwiftSteps(context.getSourcePathResolver(), commands);

    // do the zipping
    commands.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                pathToOutputFile.getParent())));

    commands.add(
        ZipStep.of(
            getProjectFilesystem(),
            pathToOutputFile.getPath(),
            ImmutableSet.of(),
            false,
            compressionLevel,
            temp.getPath()));

    buildableContext.recordArtifact(
        context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput()).getPath());

    return commands.build();
  }

  private void appendAdditionalSwiftSteps(
      SourcePathResolverAdapter resolver, ImmutableList.Builder<Step> commands) {
    // For .ipas containing Swift code, Apple requires the following for App Store submissions:
    // 1. Copy the Swift standard libraries to SwiftSupport/{platform}
    if (bundle instanceof AppleBundle) {
      AppleBundle appleBundle = (AppleBundle) bundle;

      Path swiftSupportDir = temp.resolve("SwiftSupport").resolve(appleBundle.getPlatformName());

      appleBundle.addSwiftStdlibStepIfNeeded(
          resolver, swiftSupportDir, Optional.empty(), commands, true /* is for packaging? */);
    }
  }

  private void appendAdditionalAppleWatchSteps(
      BuildContext context, ImmutableList.Builder<Step> commands) {
    // For .ipas with WatchOS2 support, Apple apparently requires the following for App Store
    // submissions:
    // 1. Have a empty "Symbols" directory on the top level.
    // 2. Copy the unmodified WatchKit stub binary for WatchOS2 apps to WatchKitSupport2/WK
    // We can't use the copy of the binary in the bundle because that has already been re-signed
    // with our own identity.
    //
    // For WatchOS1 support: same as above, except:
    // 1. No "Symbols" directory needed.
    // 2. WatchKitSupport instead of WatchKitSupport2.
    for (BuildRule rule : bundle.getBuildDeps()) {
      if (rule instanceof AppleBundle) {
        AppleBundle appleBundle = (AppleBundle) rule;
        BuildRule binary = appleBundle.getBinaryBuildRule();
        if (binary instanceof CopyFile && appleBundle.getPlatformName().startsWith("watch")) {
          commands.add(
              MkdirStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      context.getBuildCellRootPath(),
                      getProjectFilesystem(),
                      temp.resolve("Symbols"))));
          Path watchKitSupportDir = temp.resolve("WatchKitSupport2");
          commands.add(
              MkdirStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      context.getBuildCellRootPath(), getProjectFilesystem(), watchKitSupportDir)));
          commands.add(
              CopyStep.forFile(
                  context
                      .getSourcePathResolver()
                      .getIdeallyRelativePath(
                          Objects.requireNonNull(binary.getSourcePathToOutput())),
                  watchKitSupportDir.resolve("WK")));
        } else {
          Optional<CopyFile> legacyWatchStub = getLegacyWatchStubFromDeps(appleBundle);
          if (legacyWatchStub.isPresent()) {
            Path watchKitSupportDir = temp.resolve("WatchKitSupport");
            commands.add(
                MkdirStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        context.getBuildCellRootPath(),
                        getProjectFilesystem(),
                        watchKitSupportDir)));
            commands.add(
                CopyStep.forFile(
                    context
                        .getSourcePathResolver()
                        .getIdeallyRelativePath(
                            Objects.requireNonNull(legacyWatchStub.get().getSourcePathToOutput())),
                    watchKitSupportDir.resolve("WK")));
          }
        }
      }
    }
  }

  /**
   * Get the stub binary rule from a legacy Apple Watch Extension build rule.
   *
   * @return the WatchOS 1 stub binary if appleBundle represents a legacy Watch Extension.
   *     Otherwise, return absent.
   */
  private Optional<CopyFile> getLegacyWatchStubFromDeps(AppleBundle appleBundle) {
    for (BuildRule rule : appleBundle.getBuildDeps()) {
      if (rule instanceof AppleBundle
          && rule.getBuildTarget()
              .getFlavors()
              .contains(AppleBinaryDescription.LEGACY_WATCH_FLAVOR)) {
        AppleBundle legacyWatchApp = (AppleBundle) rule;
        BuildRule legacyWatchStub = legacyWatchApp.getBinaryBuildRule();
        if (legacyWatchStub instanceof CopyFile) {
          return Optional.of((CopyFile) legacyWatchStub);
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutputFile);
  }

  @Override
  public boolean isCacheable() {
    return bundle.isCacheable();
  }
}
