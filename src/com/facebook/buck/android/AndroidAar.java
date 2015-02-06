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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class AndroidAar extends AbstractBuildRule {

  private final Path pathToOutputFile;
  private final Path temp;
  private final AndroidManifest manifest;
  private final AndroidResource androidResource;
  private final AndroidLibrary androidLibrary;
  private final AssembleDirectories assembleResourceDirectories;
  private final AssembleDirectories assembleAssetsDirectories;

  public AndroidAar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AndroidManifest manifest,
      AndroidResource androidResource,
      AndroidLibrary androidLibrary,
      AssembleDirectories assembleResourceDirectories,
      AssembleDirectories assembleAssetsDirectories) {
    super(params, resolver);
    BuildTarget buildTarget = params.getBuildTarget();
    this.pathToOutputFile = BuildTargets.getGenPath(buildTarget, "%s.aar");
    this.temp = BuildTargets.getBinPath(buildTarget, "__temp__%s");
    this.manifest = manifest;
    this.androidResource = androidResource;
    this.androidLibrary = androidLibrary;
    this.assembleAssetsDirectories = assembleAssetsDirectories;
    this.assembleResourceDirectories = assembleResourceDirectories;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.<Path>of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Create temp folder to store the files going to be zipped
    commands.add(new MakeCleanDirectoryStep(temp));

    // Remove the output .aar file
    commands.add(new RmStep(pathToOutputFile, /* shouldForceDeletion */ true));

    // put manifest into tmp folder
    commands.add(
        CopyStep.forFile(
            manifest.getPathToOutputFile(),
            temp.resolve("AndroidManifest.xml")));

    // put R.txt into tmp folder
    commands.add(CopyStep.forFile(androidResource.getPathToOutputFile(), temp.resolve("R.txt")));

    // put res/ and assets/ into tmp folder
    commands.add(CopyStep.forDirectory(
            assembleResourceDirectories.getPathToOutputFile(),
            temp.resolve("res"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));
    commands.add(CopyStep.forDirectory(
            assembleAssetsDirectories.getPathToOutputFile(),
            temp.resolve("assets"),
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    // put .jar into tmp folder
    Path jar = androidLibrary.getPathToOutputFile();
    if (jar != null) {
      commands.add(CopyStep.forFile(jar, temp.resolve("classes.jar")));
    }
    // TODO(user) if there is neither src nor resource, there is no JAR,
    // in such case we need to generate an empty JAR, and copy the dependence to /libs folder

    // do the zipping
    commands.add(
        new ZipStep(
            pathToOutputFile,
            ImmutableSet.<Path>of(),
            false,
            ZipStep.DEFAULT_COMPRESSION_LEVEL,
            temp));

    return commands.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

}
