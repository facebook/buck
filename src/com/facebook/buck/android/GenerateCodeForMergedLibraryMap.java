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

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;

/**
 * Rule to write the results of library merging to disk and run a user-supplied code generator on
 * it.
 */
class GenerateCodeForMergedLibraryMap extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey(stringify = true)
  private final ImmutableSortedMap<String, NativeLibraryMergeEnhancer.SonameMergeData> mergeResult;

  @AddToRuleKey private final BuildRule codeGenerator;

  @AddToRuleKey
  private final ImmutableSortedMap<String, ImmutableSortedSet<String>> sharedObjectTargets;

  @AddToRuleKey private final boolean withDownwardApi;

  GenerateCodeForMergedLibraryMap(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSortedMap<String, NativeLibraryMergeEnhancer.SonameMergeData> mergeResult,
      ImmutableSortedMap<String, ImmutableSortedSet<String>> sharedObjectTargets,
      BuildRule codeGenerator,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.mergeResult = mergeResult;
    this.sharedObjectTargets = sharedObjectTargets;
    this.codeGenerator = codeGenerator;
    this.withDownwardApi = withDownwardApi;

    if (!(codeGenerator instanceof BinaryBuildRule)) {
      throw new HumanReadableException(
          String.format(
              "For build rule %s, code generator %s is not executable but must be",
              getBuildTarget(), codeGenerator.getBuildTarget()));
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    RelPath output = context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput());
    buildableContext.recordArtifact(output.getPath());
    buildableContext.recordArtifact(getMappingPath().getPath());
    buildableContext.recordArtifact(getTargetsPath().getPath());
    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())))
        .add(new WriteMapDataStep())
        .add(new WriteTargetsFileStep())
        .add(
            new RunCodeGenStep(
                context.getSourcePathResolver(),
                ProjectFilesystemUtils.relativize(
                    getProjectFilesystem().getRootPath(), context.getBuildCellRootPath()),
                withDownwardApi))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(),
            getBuildTarget(),
            "%s/MergedLibraryMapping.java"));
  }

  private RelPath getMappingPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/merged_library_map.txt");
  }

  /**
   * This file shows which targets went into which libraries. It's just meant for human consumption
   * when writing merge configs.
   */
  private RelPath getTargetsPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem().getBuckPaths(), getBuildTarget(), "%s/shared_object_targets.txt");
  }

  private class WriteMapDataStep implements Step {
    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      ProjectFilesystem projectFilesystem = getProjectFilesystem();
      try (Writer out =
          new BufferedWriter(
              new OutputStreamWriter(
                  projectFilesystem.newFileOutputStream(getMappingPath().getPath())))) {
        for (Map.Entry<String, NativeLibraryMergeEnhancer.SonameMergeData> entry :
            mergeResult.entrySet()) {
          if (!entry.getValue().getIncludeInAndroidMergeMapOutput()) {
            continue;
          }
          out.write(entry.getKey());
          out.write(' ');
          out.write(entry.getValue().getSoname());
          out.write('\n');
        }
      }

      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write_merged_library_map";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return String.format("%s > %s", getShortName(), getMappingPath());
    }
  }

  private class WriteTargetsFileStep implements Step {
    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      ProjectFilesystem projectFilesystem = getProjectFilesystem();
      try (Writer out =
          new BufferedWriter(
              new OutputStreamWriter(
                  projectFilesystem.newFileOutputStream(getTargetsPath().getPath())))) {
        for (Map.Entry<String, ImmutableSortedSet<String>> entry : sharedObjectTargets.entrySet()) {
          out.write(entry.getKey());
          for (String target : entry.getValue()) {
            out.write(' ');
            out.write(target);
          }
          out.write('\n');
        }
      }

      return StepExecutionResults.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write_shared_objects_target";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return String.format("%s > %s", getShortName(), getMappingPath());
    }
  }

  private class RunCodeGenStep extends IsolatedShellStep {
    private final SourcePathResolverAdapter pathResolver;

    RunCodeGenStep(
        SourcePathResolverAdapter pathResolver, RelPath cellPath, boolean withDownwardApi) {
      super(getProjectFilesystem().getRootPath(), cellPath, withDownwardApi);
      this.pathResolver = pathResolver;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
      Preconditions.checkState(
          GenerateCodeForMergedLibraryMap.this.codeGenerator instanceof BinaryBuildRule);
      String executableCommand =
          Joiner.on(" ")
              .join(
                  ((BinaryBuildRule) GenerateCodeForMergedLibraryMap.this.codeGenerator)
                      .getExecutableCommand(OutputLabel.defaultLabel())
                      .getCommandPrefix(pathResolver));
      return ImmutableList.<String>builder()
          .addAll(Splitter.on(' ').split(executableCommand))
          .add(getMappingPath().toString())
          .add(pathResolver.getCellUnsafeRelPath(getSourcePathToOutput()).toString())
          .build();
    }

    @Override
    public String getShortName() {
      return "run_merged_lib_code_generator";
    }
  }
}
