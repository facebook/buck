/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

public class ShBinary extends AbstractBuildRule
    implements BinaryBuildRule, InitializableFromDisk<Object> {

  private final SourcePath main;
  private final ImmutableSet<SourcePath> resources;

  /** The path where the output will be written. */
  private final Path output;

  private final BuildOutputInitializer<Object> buildOutputInitializer;

  protected ShBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath main,
      ImmutableSet<SourcePath> resources) {
    super(params, resolver);
    this.main = main;
    this.resources = resources;

    BuildTarget target = params.getBuildTarget();
    this.output = BuildTargets.getGenPath(
        target,
        String.format("__%%s__/%s.sh", target.getShortNameAndFlavorPostfix()));
    this.buildOutputInitializer = new BuildOutputInitializer<>(target, this);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableSortedSet<SourcePath> allPaths = ImmutableSortedSet.<SourcePath>naturalOrder()
        .add(main)
        .addAll(resources)
        .build();

    return getResolver().filterInputsToCompareToOutput(allPaths);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    MakeCleanDirectoryStep mkdir = new MakeCleanDirectoryStep(output.getParent());

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    GenerateShellScriptStep generateShellScript = new GenerateShellScriptStep(
        getBuildTarget().getBasePath(),
        getResolver().getPath(main),
        getResolver().getAllPaths(resources),
        output);

    buildableContext.recordArtifact(output);
    return ImmutableList.of(mkdir, generateShellScript);
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(projectFilesystem
          .getFileForRelativePath(output.toString())
          .getAbsolutePath()
          .toString());
  }

  /*
   * This method implements InitializableFromDisk so that it can make the output file
   * executable when this rule is populated from cache. The buildOutput Object is meaningless:
   * it is created only to satisfy InitializableFromDisk contract.
   * TODO(task #3321496): Delete this entire interface implementation after we fix zipping exe's.
   */
  @Override
  public Object initializeFromDisk(OnDiskBuildInfo info) {
    try {
      info.makeOutputFileExecutable(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
