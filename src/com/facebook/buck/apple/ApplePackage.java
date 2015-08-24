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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class ApplePackage extends AbstractBuildRule {

  private final Path pathToOutputFile;
  private final Path temp;
  private final AppleBundle bundle;

  public ApplePackage(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleBundle bundle) {
    super(params, resolver);
    BuildTarget buildTarget = params.getBuildTarget();
    // TODO(user): This will be different for Mac apps.
    this.pathToOutputFile = BuildTargets.getGenPath(buildTarget, "%s.ipa");
    this.temp = BuildTargets.getScratchPath(buildTarget, "__temp__%s");
    this.bundle = bundle;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Create temp folder to store the files going to be zipped
    commands.add(new MakeCleanDirectoryStep(temp));

    Path payloadDir = temp.resolve("Payload");
    commands.add(new MkdirStep(payloadDir));

    // Remove the output .ipa file if it exists already
    commands.add(new RmStep(pathToOutputFile, /* shouldForceDeletion */ true));

    // Recursively copy the .app directory into the Payload folder
    commands.add(CopyStep.forDirectory(
            bundle.getPathToOutput(),
            payloadDir,
            CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));

    // do the zipping
    commands.add(
        new ZipStep(
            pathToOutputFile,
            ImmutableSet.<Path>of(),
            false,
            ZipStep.DEFAULT_COMPRESSION_LEVEL,
            temp));

    buildableContext.recordArtifact(getPathToOutput());

    return commands.build();
  }

  @Override
  public Path getPathToOutput() {
    return pathToOutputFile;
  }
}
