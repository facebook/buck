/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A rule similar to {@link com.facebook.buck.shell.Genrule} except specialized to produce a jar.
 *
 * <p>The produced jar behaves similarly to a jar produced by java_binary, which means it can be
 * executed by {@code buck run} or using the {@code $(exe )} macro.
 */
public class JarGenrule extends Genrule implements BinaryBuildRule {

  // Only used by getExecutableCommand, does not need to contribute to the rule key.
  private final Tool javaRuntimeLauncher;

  // Fully determined by the target, does not need to contribute to the rule key.
  private final Path pathToOutput;

  protected JarGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      BuildRuleResolver resolver,
      BuildRuleParams params,
      List<SourcePath> srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      String out,
      boolean isCacheable,
      Optional<String> environmentExpansionSeparator,
      Tool javaRuntimeLauncher) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        params,
        sandboxExecutionStrategy,
        srcs,
        cmd,
        bash,
        cmdExe,
        type,
        out,
        false,
        isCacheable,
        environmentExpansionSeparator,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        false);
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.pathToOutput = BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s.jar");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutput);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(pathToOutput);

    // Ask the super class for its own steps that will produce the jar and then move the jar
    // to the expected output location.
    return ImmutableList.<Step>builder()
        .add(
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), pathToOutput)))
        .addAll(getBuildStepsWithoutRecordingOutput(context))
        .add(new MoveStep(getProjectFilesystem(), pathToOutFile, pathToOutput))
        .build();
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(javaRuntimeLauncher)
        .addArg("-jar")
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }
}
