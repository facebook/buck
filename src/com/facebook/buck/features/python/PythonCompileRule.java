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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
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
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
      boolean ignoreErrors,
      boolean withDownwardApi) {
    return new PythonCompileRule(
        target, filesystem, ruleFinder, new Impl(python, sources, ignoreErrors, withDownwardApi));
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
    @AddToRuleKey private final boolean withDownwardApi;

    public Impl(
        PythonEnvironment python,
        PythonComponents sources,
        boolean ignoreErrors,
        boolean withDownwardApi) {
      this.python = python;
      this.sources = sources;
      this.ignoreErrors = ignoreErrors;
      this.withDownwardApi = withDownwardApi;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.<Step>builder()
          .addAll(
              MakeCleanDirectoryStep.of(
                  buildCellPathFactory.from(outputPathResolver.resolvePath(OUTPUT))))
          .add(
              new AbstractExecutionStep("py-compile") {

                private final ImmutableList<String> pyCommand =
                    python.getCommandPrefix(buildContext.getSourcePathResolver());
                private final RelPath output = outputPathResolver.resolvePath(OUTPUT);
                private final Path argsfile = outputPathResolver.getTempPath("py_compile_args");
                private final PythonComponents.Resolved resolvedSources =
                    sources.resolvePythonComponents(buildContext.getSourcePathResolver());

                @Override
                public StepExecutionResult execute(StepExecutionContext context)
                    throws IOException, InterruptedException {
                  ImmutableList.Builder<String> builder = ImmutableList.builder();
                  builder.addAll(pyCommand);
                  builder.add("-c", getCompiler());
                  builder.add("--output=" + output);
                  if (ignoreErrors) {
                    builder.add("--ignore-errors");
                  }

                  // Write python source list to argsfile to avoid command length limits.
                  try (BufferedWriter writer =
                      new BufferedWriter(
                          new OutputStreamWriter(
                              Files.newOutputStream(
                                  context.getRuleCellRoot().resolve(argsfile).getPath()),
                              Charsets.UTF_8))) {
                    resolvedSources.forEachPythonComponent(
                        (dst, src) -> {
                          writer.write(
                              String.format("%s=%s", PythonUtil.toModuleName(dst.toString()), src));
                          writer.newLine();
                        });
                  }
                  builder.add("@" + argsfile);

                  ImmutableList<String> command = builder.build();
                  ProcessExecutor processExecutor = context.getProcessExecutor();
                  if (withDownwardApi) {
                    processExecutor =
                        processExecutor.withDownwardAPI(
                            DownwardApiProcessExecutor.FACTORY, context.getBuckEventBus());
                  }
                  return StepExecutionResult.of(
                      processExecutor.launchAndExecute(
                          ProcessExecutorParams.builder()
                              .setDirectory(context.getRuleCellRoot().getPath())
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
          Resources.getResource(PythonCompileRule.class, "compile.py"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
