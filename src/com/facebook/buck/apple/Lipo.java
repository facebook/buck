/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

public class Lipo extends ModernBuildRule<Lipo.Impl> {

  public Lipo(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool lipo,
      SourcePath sourcePath,
      OutputPath output,
      ImmutableList<String> lipoArgs) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(lipo, sourcePath, output, lipoArgs));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  /** Rule implementation. */
  static class Impl implements Buildable {

    @AddToRuleKey private final Tool lipo;

    @AddToRuleKey(stringify = true)
    private final OutputPath outputPath;

    @AddToRuleKey private final SourcePath sourcePath;

    @AddToRuleKey private final ImmutableList<String> args;

    Impl(Tool lipo, SourcePath sourcePath, OutputPath outputPath, ImmutableList<String> args) {
      this.lipo = lipo;
      this.outputPath = outputPath;
      this.sourcePath = sourcePath;
      this.args = args;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList.Builder<Step> builder = ImmutableList.builder();

      RelPath output = outputPathResolver.resolvePath(outputPath);
      if (output.getParent() != null) {
        builder.add(MkdirIsolatedStep.of(output.getParent()));
      }

      ImmutableList.Builder<String> command = ImmutableList.builder();
      command.addAll(lipo.getCommandPrefix(buildContext.getSourcePathResolver()));
      command.add(
          buildContext.getSourcePathResolver().getIdeallyRelativePath(sourcePath).toString());
      command.addAll(args);
      command.add("-output");
      command.add(outputPathResolver.resolvePath(outputPath).toString());
      builder.add(new LipoStep(command.build()));

      return builder.build();
    }
  }

  static class LipoStep implements Step {

    private final ImmutableList<String> args;

    LipoStep(ImmutableList<String> args) {
      this.args = args;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context)
        throws IOException, InterruptedException {
      ProcessExecutorParams params = ProcessExecutorParams.builder().setCommand(args).build();
      ProcessExecutor processExecutor = context.getProcessExecutor();
      ProcessExecutor.Result result = processExecutor.launchAndExecute(params);
      return StepExecutionResult.of(result);
    }

    @Override
    public String getShortName() {
      return "lipo";
    }

    @Override
    public String getDescription(StepExecutionContext context) {
      return String.join(" ", args);
    }
  }
}
