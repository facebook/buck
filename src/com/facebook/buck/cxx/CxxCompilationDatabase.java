/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class CxxCompilationDatabase extends AbstractBuildRule implements HasRuntimeDeps {
  private static final Logger LOG = Logger.get(CxxCompilationDatabase.class);
  public static final Flavor COMPILATION_DATABASE = InternalFlavor.of("compilation-database");
  public static final Flavor UBER_COMPILATION_DATABASE =
      InternalFlavor.of("uber-compilation-database");

  @AddToRuleKey private final ImmutableSortedSet<CxxPreprocessAndCompile> compileRules;

  @AddToRuleKey(stringify = true)
  private final Path outputJsonFile;

  private final ImmutableSortedSet<BuildRule> runtimeDeps;

  public static CxxCompilationDatabase createCompilationDatabase(
      BuildRuleParams params, Iterable<CxxPreprocessAndCompile> compileAndPreprocessRules) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<CxxPreprocessAndCompile> compileRules =
        ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessAndCompile compileRule : compileAndPreprocessRules) {
      compileRules.add(compileRule);
      deps.addAll(compileRule.getBuildDeps());
    }

    return new CxxCompilationDatabase(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        compileRules.build(),
        deps.build());
  }

  CxxCompilationDatabase(
      BuildRuleParams buildRuleParams,
      ImmutableSortedSet<CxxPreprocessAndCompile> compileRules,
      ImmutableSortedSet<BuildRule> runtimeDeps) {
    super(buildRuleParams);
    LOG.debug(
        "Creating compilation database %s with runtime deps %s",
        buildRuleParams.getBuildTarget(), runtimeDeps);
    this.compileRules = compileRules;
    this.outputJsonFile =
        BuildTargets.getGenPath(
            getProjectFilesystem(), buildRuleParams.getBuildTarget(), "__%s.json");
    this.runtimeDeps = runtimeDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(MkdirStep.of(getProjectFilesystem(), outputJsonFile.getParent()));
    steps.add(
        new GenerateCompilationCommandsJson(
            context.getSourcePathResolver(),
            context.getSourcePathResolver().getRelativePath(getSourcePathToOutput())));
    return steps.build();
  }

  @Override
  public boolean isCacheable() {
    // We don't want to cache the output of this rule because it contains absolute paths.
    return false;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputJsonFile);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    // The compilation database contains commands which refer to a
    // particular state of generated header symlink trees/header map
    // files.
    //
    // Ensure even if this rule doesn't need to be built due to a
    // cache hit on the (empty) output of the rule, we still fetch and
    // lay out the headers so the resulting compilation database can
    // be used.
    return runtimeDeps.stream().map(BuildRule::getBuildTarget);
  }

  class GenerateCompilationCommandsJson extends AbstractExecutionStep {

    private final SourcePathResolver pathResolver;
    private final Path outputRelativePath;

    public GenerateCompilationCommandsJson(
        SourcePathResolver pathResolver, Path outputRelativePath) {
      super("generate compile_commands.json");
      this.pathResolver = pathResolver;
      this.outputRelativePath = outputRelativePath;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) {
      Iterable<CxxCompilationDatabaseEntry> entries = createEntries();
      return StepExecutionResult.of(writeOutput(entries, context));
    }

    @VisibleForTesting
    Iterable<CxxCompilationDatabaseEntry> createEntries() {
      List<CxxCompilationDatabaseEntry> entries = new ArrayList<>();
      for (CxxPreprocessAndCompile compileRule : compileRules) {
        entries.add(createEntry(compileRule));
      }
      return entries;
    }

    private CxxCompilationDatabaseEntry createEntry(CxxPreprocessAndCompile compileRule) {

      SourcePath inputSourcePath = compileRule.getInput();
      ProjectFilesystem inputFilesystem = compileRule.getProjectFilesystem();

      String fileToCompile =
          inputFilesystem.resolve(pathResolver.getAbsolutePath(inputSourcePath)).toString();
      ImmutableList<String> arguments = compileRule.getCommand(pathResolver);
      return CxxCompilationDatabaseEntry.of(
          inputFilesystem.getRootPath().toString(), fileToCompile, arguments);
    }

    private int writeOutput(
        Iterable<CxxCompilationDatabaseEntry> entries, ExecutionContext context) {
      try (OutputStream outputStream =
          getProjectFilesystem().newFileOutputStream(outputRelativePath)) {
        ObjectMappers.WRITER.writeValue(outputStream, entries);
      } catch (IOException e) {
        logError(e, context);
        return 1;
      }

      return 0;
    }

    private void logError(Throwable throwable, ExecutionContext context) {
      context.logError(
          throwable, "Failed writing to %s in %s.", outputRelativePath, getBuildTarget());
    }
  }
}
