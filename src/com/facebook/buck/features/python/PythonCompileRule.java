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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;

/** Compile the given module sources into their respective bytecode. */
public class PythonCompileRule extends ModernBuildRule<PythonCompileRule.Impl> {

  // The compiled source directory, as `PythonComponents`, cached here so that we get hits in
  // the rule key cache (which is keyed via object instances).
  private final PythonModuleDirComponents compiledSources;

  private PythonCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      Impl buildable) {
    super(buildTarget, filesystem, ruleFinder, buildable);
    this.compiledSources = PythonModuleDirComponents.of(getSourcePath(Impl.OUTPUT));
  }

  public static PythonCompileRule from(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      PythonEnvironment python,
      PythonComponents sources,
      boolean ignoreErrors) {
    return new PythonCompileRule(
        target, filesystem, ruleFinder, new Impl(python, sources, ignoreErrors));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return compiledSources.getDirectory();
  }

  public PythonModuleDirComponents getCompiledSources() {
    return compiledSources;
  }

  /** internal buildable implementation */
  static class Impl implements Buildable {

    private static final OutputPath OUTPUT = new OutputPath("py-compile");

    @AddToRuleKey private final PythonEnvironment python;

    @AddToRuleKey private final PythonComponents sources;

    @AddToRuleKey private final boolean ignoreErrors;

    public Impl(PythonEnvironment python, PythonComponents sources, boolean ignoreErrors) {
      this.python = python;
      this.sources = sources;
      this.ignoreErrors = ignoreErrors;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      PythonComponents.Resolved resolvedSources =
          sources.resolvePythonComponents(buildContext.getSourcePathResolver());
      return ImmutableList.<Step>builder()
          .addAll(
              MakeCleanDirectoryStep.of(
                  buildCellPathFactory.from(outputPathResolver.resolvePath(OUTPUT))))
          .add(
              new AbstractExecutionStep("py-compile") {
                @Override
                public StepExecutionResult execute(ExecutionContext context)
                    throws IOException, InterruptedException {
                  ImmutableList.Builder<String> builder = ImmutableList.builder();
                  builder.addAll(python.getCommandPrefix(buildContext.getSourcePathResolver()));
                  builder.add("-c", getCompiler());
                  builder.add("--output=" + outputPathResolver.resolvePath(OUTPUT));
                  if (ignoreErrors) {
                    builder.add("--ignore-errors");
                  }
                  resolvedSources.forEachPythonComponent(
                      (dst, src) ->
                          builder.add(
                              String.format(
                                  "%s=%s", PythonUtil.toModuleName(dst.toString()), src)));
                  ImmutableList<String> command = builder.build();
                  return StepExecutionResult.of(
                      context
                          .getProcessExecutor()
                          .launchAndExecute(
                              ProcessExecutorParams.builder()
                                  .setDirectory(context.getBuildCellRootPath())
                                  // On some platforms (e.g. linux), python hash code randomness can
                                  // cause the bytecode to be non-deterministic, so pin via the
                                  // `PYTHONHASHSEED` env var.
                                  .setEnvironment(ImmutableMap.of("PYTHONHASHSEED", "7"))
                                  .setCommand(command)
                                  .build()));
                }
              })
          .build();
    }
  }

  private static String getCompiler() {
    try {
      return Resources.toString(
          Resources.getResource(PythonCompileRule.class, "compile.py"), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
