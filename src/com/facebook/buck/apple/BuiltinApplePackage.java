/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;

import java.nio.file.Path;

public class BuiltinApplePackage extends AbstractBuildRule {

  private final Path pathToOutputFile;
  private final Path temp;
  private final BuildRule bundle;

  public BuiltinApplePackage(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRule bundle) {
    super(params, resolver);
    BuildTarget buildTarget = params.getBuildTarget();
    // TODO(ryu2): This will be different for Mac apps.
    this.pathToOutputFile = BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "%s.ipa");
    this.temp = BuildTargets.getScratchPath(getProjectFilesystem(), buildTarget, "__temp__%s");
    this.bundle = bundle;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    // Remove the output .ipa file if it exists already
    commands.add(new RmStep(getProjectFilesystem(), pathToOutputFile, /* force delete */ true));

    // Create temp folder to store the files going to be zipped
    commands.add(new MakeCleanDirectoryStep(getProjectFilesystem(), temp));

    Path payloadDir = temp.resolve("Payload");
    commands.add(new MkdirStep(getProjectFilesystem(), payloadDir));

    // Recursively copy the .app directory into the Payload folder
    Path bundleOutputPath = bundle.getPathToOutput();

    appendAdditionalAppleWatchSteps(commands);

    commands.add(CopyStep.forDirectory(
            getProjectFilesystem(),
            bundleOutputPath,
            payloadDir,
            CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));

    // do the zipping
    commands.add(new MkdirStep(getProjectFilesystem(), pathToOutputFile.getParent()));
    commands.add(
        new ZipStep(
            getProjectFilesystem(),
            pathToOutputFile,
            ImmutableSet.<Path>of(),
            false,
            ZipCompressionLevel.DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(getPathToOutput());

    return commands.build();
  }

  private void appendAdditionalAppleWatchSteps(ImmutableList.Builder<Step> commands) {
    // For .ipas with WatchOS2 support, Apple apparently requires the following for App Store
    // submissions:
    // 1. Have a empty "Symbols" directory on the top level.
    // 2. Copy the unmodified WatchKit stub binary for WatchOS2 apps to WatchKitSupport2/WK
    // We can't use the copy of the binary in the bundle because that has already been re-signed
    // with our own identity.
    for (BuildRule rule : bundle.getDeps()) {
      if (rule instanceof AppleBundle) {
        AppleBundle appleBundle = (AppleBundle) rule;
        if (appleBundle.getBinary().isPresent() &&
            appleBundle.getPlatformName().startsWith("watch")) {
          BuildRule binary = appleBundle.getBinary().get();
          if (binary instanceof WriteFile) {
            commands.add(new MkdirStep(getProjectFilesystem(), temp.resolve("Symbols")));
            Path watchKitSupportDir = temp.resolve("WatchKitSupport2");
            commands.add(new MkdirStep(getProjectFilesystem(), watchKitSupportDir));
            commands.add(new WriteFileStep(
                getProjectFilesystem(),
                ByteSource.wrap(((WriteFile) binary).getFileContents()),
                watchKitSupportDir.resolve("WK"),
                true /* executable */
            ));
          }
        }
      }
    }
  }

  @Override
  public Path getPathToOutput() {
    return pathToOutputFile;
  }
}
