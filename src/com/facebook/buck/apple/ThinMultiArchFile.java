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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.HasAppleDebugSymbolDeps;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Thin a multi-arch file library/binaries into a thin rule */
public class ThinMultiArchFile extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasAppleDebugSymbolDeps {

  @AddToRuleKey private final Tool lipo;

  @AddToRuleKey private final SourcePath maybeFatBinary;
  @AddToRuleKey private String targetArchitecture;

  @AddToRuleKey(stringify = true)
  private final Path output;

  public ThinMultiArchFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool lipo,
      SourcePath maybeFatBinary,
      String targetArchitecture) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.lipo = lipo;
    this.maybeFatBinary = maybeFatBinary;
    this.targetArchitecture = targetArchitecture;
    this.output = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    lipoBinaries(context, steps);

    return steps.build();
  }

  private void lipoBinaries(BuildContext context, ImmutableList.Builder<Step> steps) {
    Path scratchPath =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s__lipo__")
            .resolve("universal.a");

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), scratchPath.getParent())));

    ImmutableList.Builder<String> createCommand = ImmutableList.builder();
    createCommand.addAll(lipo.getCommandPrefix(context.getSourcePathResolver()));
    createCommand.add(
        context.getSourcePathResolver().getAbsolutePath(maybeFatBinary).toString(),
        "-create",
        "-output",
        getProjectFilesystem().resolve(scratchPath).toString());
    steps.add(
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            createCommand.build(),
            lipo.getEnvironment(context.getSourcePathResolver())));

    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(lipo.getCommandPrefix(context.getSourcePathResolver()));
    commandBuilder.add(
        getProjectFilesystem().resolve(scratchPath).toString(),
        "-extract",
        this.targetArchitecture,
        "-output",
        getProjectFilesystem().resolve(output).toString());
    steps.add(
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            commandBuilder.build(),
            lipo.getEnvironment(context.getSourcePathResolver())));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Stream<BuildRule> getAppleDebugSymbolDeps() {
    return RichStream.from(getBuildDeps())
        .filter(HasAppleDebugSymbolDeps.class)
        .flatMap(HasAppleDebugSymbolDeps::getAppleDebugSymbolDeps);
  }
}
