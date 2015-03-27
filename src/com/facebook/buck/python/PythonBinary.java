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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class PythonBinary extends AbstractBuildRule implements BinaryBuildRule {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  private final Path pathToPex;
  private final Path pathToPexExecuter;
  private final Path main;
  private final PythonPackageComponents components;
  private final PythonEnvironment pythonEnvironment;

  protected PythonBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path pathToPex,
      Path pathToPexExecuter,
      PythonEnvironment pythonEnvironment,
      Path main,
      PythonPackageComponents components) {
    super(params, resolver);
    this.pathToPex = pathToPex;
    this.pathToPexExecuter = pathToPexExecuter;
    this.pythonEnvironment = pythonEnvironment;
    this.main = main;
    this.components = components;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public Path getBinPath() {
    return BuildTargets.getScratchPath(getBuildTarget(), "%s.pex");
  }

  @Override
  public Path getPathToOutputFile() {
    return getBinPath();
  }

  @VisibleForTesting
  protected PythonPackageComponents getComponents() {
    return components;
  }

  @VisibleForTesting
  protected Path getMain() {
    return main;
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(
        pathToPexExecuter.toString(),
        Preconditions.checkNotNull(
            projectFilesystem.getAbsolutifier().apply(getBinPath())).toString());
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("packageType", "pex")
        .setReflectively("pythonVersion", pythonEnvironment.getPythonVersion().toString())
        .setReflectively("mainModule", main.toString())
        .setReflectively("components", components);
    return builder;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.copyOf(getResolver().filterInputsToCompareToOutput(
        components.getInputsToCompareToOutput()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path binPath = getBinPath();

    // Make sure the parent directory exists.
    steps.add(new MkdirStep(binPath.getParent()));

    Path workingDirectory = BuildTargets.getGenPath(
        getBuildTarget(), "__%s__working_directory");
    steps.add(new MakeCleanDirectoryStep(workingDirectory));

    // Generate and return the PEX build step.
    steps.add(new PexStep(
        pathToPex,
        pythonEnvironment.getPythonPath(),
        workingDirectory,
        binPath,
        PythonUtil.toModuleName(getBuildTarget(), main.toString()),
        getResolver().getMappedPaths(components.getModules()),
        getResolver().getMappedPaths(components.getResources()),
        getResolver().getMappedPaths(components.getNativeLibraries())));

    // Record the executable package for caching.
    buildableContext.recordArtifact(getBinPath());

    return steps.build();
  }

}
