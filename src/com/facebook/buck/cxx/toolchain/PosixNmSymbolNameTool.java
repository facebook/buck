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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * A {@link SymbolNameTool} implementation using a POSIX-compliant `nm` utility
 * (http://pubs.opengroup.org/onlinepubs/009696699/utilities/nm.html).
 */
public class PosixNmSymbolNameTool implements SymbolNameTool {

  private final ToolProvider nm;
  private final boolean withDownwardApi;

  public PosixNmSymbolNameTool(ToolProvider nm, boolean withDownwardApi) {
    this.nm = nm;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public SourcePath createUndefinedSymbolsFile(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs) {
    Tool nm = this.nm.resolve(graphBuilder, targetConfiguration);
    UndefinedSymbolsFile rule =
        graphBuilder.addToIndex(
            new UndefinedSymbolsFile(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(BuildableSupport.getDepsCollection(nm, graphBuilder))
                            .addAll(graphBuilder.filterBuildRuleInputs(linkerInputs))
                            .build())
                    .withoutExtraDeps(),
                nm,
                linkerInputs,
                withDownwardApi));
    return rule.getSourcePathToOutput();
  }

  @Override
  public SourcePath creatGlobalSymbolsFile(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs) {
    Tool nm = this.nm.resolve(graphBuilder, targetConfiguration);
    GlobalSymbolsFile rule =
        graphBuilder.addToIndex(
            new GlobalSymbolsFile(
                target,
                projectFilesystem,
                baseParams
                    .withDeclaredDeps(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(BuildableSupport.getDepsCollection(nm, graphBuilder))
                            .addAll(graphBuilder.filterBuildRuleInputs(linkerInputs))
                            .build())
                    .withoutExtraDeps(),
                nm,
                linkerInputs,
                withDownwardApi));
    return rule.getSourcePathToOutput();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return nm.getParseTimeDeps(targetConfiguration);
  }

  private abstract static class SymbolsFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

    @AddToRuleKey private final Tool nm;
    @AddToRuleKey private final Iterable<? extends SourcePath> inputs;
    @AddToRuleKey private final boolean withDownwardApi;

    public SymbolsFile(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Tool nm,
        Iterable<? extends SourcePath> inputs,
        boolean withDownwardApi) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.nm = nm;
      this.inputs = inputs;
      this.withDownwardApi = withDownwardApi;
    }

    abstract String getSymbolsType();

    abstract ImmutableList<String> getAdditionalCommandArgs();

    private RelPath getSymbolsPath() {
      String format = String.format("%%s/%s_symbols.txt", getSymbolsType());
      return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), format);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      RelPath output = getSymbolsPath();

      // Cache the symbols file.
      buildableContext.recordArtifact(output.getPath());

      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      // Start with a clean parent dir.
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

      // Parse the output from running `nm` and write all symbols to the symbol file.
      steps.add(
          new IsolatedStep() {

            private final Path symsFile = context.getBuildCellRootPath().resolve(output.getPath());
            private final ImmutableMap<String, String> env =
                nm.getEnvironment(context.getSourcePathResolver());
            private final ImmutableList<String> cmd =
                ImmutableList.<String>builder()
                    .addAll(nm.getCommandPrefix(context.getSourcePathResolver()))
                    // Prepend all lines with the name of the input file to which it
                    // corresponds.  Added only to make parsing the output a bit easier.
                    .add("-A")
                    // Generate output in a portable output format.
                    .add("-P")
                    .addAll(getAdditionalCommandArgs())
                    .addAll(
                        StreamSupport.stream(inputs.spliterator(), false)
                            .map(context.getSourcePathResolver()::getAbsolutePath)
                            .map(Object::toString)
                            .iterator())
                    .build();

            @Override
            public String getIsolatedStepDescription(IsolatedExecutionContext context) {
              return String.format(
                  "Use POSIX nm to read %s symbols and write them to a file", getSymbolsType());
            }

            @Override
            public String getShortName() {
              return String.format("generate-%s-symbols", getSymbolsType());
            }

            @Override
            public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
                throws IOException, InterruptedException {
              Pattern pattern = Pattern.compile("^\\S+: (?<name>\\S+) .*");

              ProcessExecutor executor = context.getProcessExecutor();
              if (withDownwardApi) {
                executor =
                    executor.withDownwardAPI(
                        DownwardApiProcessExecutor.FACTORY, context.getIsolatedEventBus());
              }

              try (ProcessExecutor.LaunchedProcess process =
                      executor.launchProcess(
                          ProcessExecutorParams.builder()
                              .setCommand(cmd)
                              .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
                              .setEnvironment(env)
                              .setDirectory(context.getRuleCellRoot().getPath())
                              .build());
                  BufferedReader reader =
                      new BufferedReader(
                          new InputStreamReader(process.getStdout(), Charsets.UTF_8));
                  BufferedWriter writer =
                      new BufferedWriter(
                          new OutputStreamWriter(
                              Files.newOutputStream(symsFile), Charsets.UTF_8))) {
                Set<String> symbols = new HashSet<>();
                String line;
                while ((line = reader.readLine()) != null) {
                  Matcher matcher = pattern.matcher(line);
                  if (matcher.matches()) {
                    String symbol = matcher.group("name");
                    if (symbols.add(symbol)) {
                      writer.append(symbol);
                      writer.newLine();
                    }
                  }
                }
                return StepExecutionResult.of(executor.waitForLaunchedProcess(process));
              }
            }
          });

      return steps.build();
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getSymbolsPath());
    }
  }

  private static class UndefinedSymbolsFile extends SymbolsFile {

    public UndefinedSymbolsFile(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Tool nm,
        Iterable<? extends SourcePath> inputs,
        boolean withDownwardApi) {
      super(buildTarget, projectFilesystem, buildRuleParams, nm, inputs, withDownwardApi);
    }

    @Override
    String getSymbolsType() {
      return "undefined";
    }

    @Override
    ImmutableList<String> getAdditionalCommandArgs() {
      return ImmutableList.<String>builder()
          // Only list external symbols.
          .add("-g")
          // Only list undefined symbols.
          .add("-u")
          .build();
    }
  }

  private static class GlobalSymbolsFile extends SymbolsFile {

    public GlobalSymbolsFile(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        Tool nm,
        Iterable<? extends SourcePath> inputs,
        boolean withDownwardApi) {
      super(buildTarget, projectFilesystem, buildRuleParams, nm, inputs, withDownwardApi);
    }

    @Override
    String getSymbolsType() {
      return "global";
    }

    @Override
    ImmutableList<String> getAdditionalCommandArgs() {
      return ImmutableList.<String>builder()
          // Only list external symbols.
          .add("-g")
          .build();
    }
  }
}
