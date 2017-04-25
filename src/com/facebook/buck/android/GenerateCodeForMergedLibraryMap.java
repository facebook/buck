/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Rule to write the results of library merging to disk and run a user-supplied code generator on
 * it.
 */
class GenerateCodeForMergedLibraryMap extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableSortedMap<String, String> mergeResult;
  @AddToRuleKey private final BuildRule codeGenerator;

  GenerateCodeForMergedLibraryMap(
      BuildRuleParams buildRuleParams,
      ImmutableSortedMap<String, String> mergeResult,
      BuildRule codeGenerator) {
    super(buildRuleParams);
    this.mergeResult = mergeResult;
    this.codeGenerator = codeGenerator;

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
    Path output = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    buildableContext.recordArtifact(output);
    buildableContext.recordArtifact(getMappingPath());
    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), output.getParent()))
        .add(new WriteMapDataStep())
        .add(new RunCodeGenStep(context.getSourcePathResolver()))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "%s/MergedLibraryMapping.java"));
  }

  private Path getMappingPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/merged_library_map.txt");
  }

  private class WriteMapDataStep implements Step {
    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      final ProjectFilesystem projectFilesystem = getProjectFilesystem();
      try (Writer out =
          new BufferedWriter(
              new OutputStreamWriter(projectFilesystem.newFileOutputStream(getMappingPath())))) {
        for (Map.Entry<String, String> entry : mergeResult.entrySet()) {
          out.write(entry.getKey());
          out.write(' ');
          out.write(entry.getValue());
          out.write('\n');
        }
      }

      return StepExecutionResult.SUCCESS;
    }

    @Override
    public String getShortName() {
      return "write_merged_library_map";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format("%s > %s", getShortName(), getMappingPath());
    }
  }

  private class RunCodeGenStep extends ShellStep {
    private final SourcePathResolver pathResolver;

    RunCodeGenStep(SourcePathResolver pathResolver) {
      super(getProjectFilesystem().getRootPath());
      this.pathResolver = pathResolver;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      String executableCommand;
      try {
        executableCommand =
            new ExecutableMacroExpander()
                .expand(pathResolver, GenerateCodeForMergedLibraryMap.this.codeGenerator);
      } catch (MacroException e) {
        // Should not be possible, as check was performed in constructor.
        throw new RuntimeException(e);
      }

      return ImmutableList.<String>builder()
          .addAll(Splitter.on(' ').split(executableCommand))
          .add(getMappingPath().toString())
          .add(pathResolver.getRelativePath(getSourcePathToOutput()).toString())
          .build();
    }

    @Override
    public String getShortName() {
      return "run_merged_lib_code_generator";
    }
  }
}
