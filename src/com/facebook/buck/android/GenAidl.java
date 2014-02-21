/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RecordArtifactsInDirectoryStep;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Buildable for generating a .java file from an .aidl file. Example:
 * <pre>
 * # This will generate IOrcaService.java in the genfiles directory.
 * gen_aidl(
 *   name = 'orcaservice',
 *   aidl = 'IOrcaService.aidl',
 * )
 *
 * # The genfile() function flags the input as a file that can be found in the buck-out/gen
 * # directory.
 * android_library(
 *   name = 'server',
 *   srcs = glob(['*.java']) + [genfile('IOrcaService.java')],
 *   deps = [
 *     '//first-party/orca/lib-base:lib-base',
 *   ],
 * )
 * </pre>
 */
public class GenAidl extends AbstractBuildable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID);

  private final BuildTarget buildTarget;
  private final Path aidlFilePath;
  private final String importPath;

  GenAidl(BuildTarget buildTarget, Path aidlFilePath, String importPath) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.aidlFilePath = Preconditions.checkNotNull(aidlFilePath);
    this.importPath = Preconditions.checkNotNull(importPath);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    // A gen_aidl() does not have a "primary output" at this time.
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    // TODO(#2493457): This rule uses the aidl binary (part of the Android SDK), so the RuleKey
    // should incorporate which version of aidl is used.
    return builder
        .set("importPath", importPath);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(aidlFilePath);
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    Path outputDirectory = Paths.get(
        BuckConstant.BIN_DIR,
        buildTarget.getBasePath(),
        "." + buildTarget.getShortName() + ".aidl");
    commands.add(new MkdirStep(outputDirectory));

    AidlStep command = new AidlStep(aidlFilePath,
        ImmutableSet.of(importPath),
        outputDirectory);
    commands.add(command);

    // Files must ultimately be written to GEN_DIR to be used with genfile().
    Path genDirectory = Paths.get(BuckConstant.GEN_DIR, importPath);
    commands.add(new MkdirStep(genDirectory));

    commands.add(new RecordArtifactsInDirectoryStep(
        buildableContext,
        outputDirectory,
        genDirectory));

    return commands.build();
  }
}
