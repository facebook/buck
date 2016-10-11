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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

public class CxxCompilationDatabase extends AbstractBuildRule
    implements HasPostBuildSteps, HasRuntimeDeps {
  private static final Logger LOG = Logger.get(CxxCompilationDatabase.class);
  public static final Flavor COMPILATION_DATABASE = ImmutableFlavor.of("compilation-database");
  public static final Flavor UBER_COMPILATION_DATABASE =
      ImmutableFlavor.of("uber-compilation-database");

  @AddToRuleKey
  private final CxxPreprocessMode preprocessMode;
  @AddToRuleKey
  private final ImmutableSortedSet<CxxPreprocessAndCompile> compileRules;
  @AddToRuleKey(stringify = true)
  private final Path outputJsonFile;
  private final ImmutableSortedSet<BuildRule> runtimeDeps;

  public static CxxCompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPreprocessMode preprocessMode,
      Iterable<CxxPreprocessAndCompile> compileAndPreprocessRules,
      Iterable<HeaderSymlinkTree> headerSymlinkTreeRuntimeDeps) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<CxxPreprocessAndCompile> compileRules = ImmutableSortedSet
        .naturalOrder();
    for (CxxPreprocessAndCompile compileRule : compileAndPreprocessRules) {
      if (CxxSourceRuleFactory.isCompileFlavoredBuildTarget(compileRule.getBuildTarget())) {
        compileRules.add(compileRule);
        deps.addAll(compileRule.getDeps());
      }
    }

    return new CxxCompilationDatabase(
        params.copyWithDeps(
            Suppliers.ofInstance(deps.build()),
            params.getExtraDeps()),
        pathResolver,
        compileRules.build(),
        preprocessMode,
        ImmutableSortedSet.copyOf(headerSymlinkTreeRuntimeDeps));
  }

  CxxCompilationDatabase(
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<CxxPreprocessAndCompile> compileRules,
      CxxPreprocessMode preprocessMode,
      ImmutableSortedSet<BuildRule> runtimeDeps) {
    super(buildRuleParams, pathResolver);
    LOG.debug(
        "Creating compilation database %s with runtime deps %s",
        buildRuleParams.getBuildTarget(),
        runtimeDeps);
    this.compileRules = compileRules;
    this.preprocessMode = preprocessMode;
    this.outputJsonFile =
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            buildRuleParams.getBuildTarget(),
            "__%s.json");
    this.runtimeDeps = runtimeDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // We don't want to cache the output of this rule because it contains absolute paths.
    // Since the step to generate the commands json output is super fast, it's ok if we always build
    // this rule locally.
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MkdirStep(getProjectFilesystem(), outputJsonFile.getParent()));
    steps.add(new GenerateCompilationCommandsJson());
    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return outputJsonFile;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    // The compilation database contains commands which refer to a
    // particular state of generated header symlink trees/header map
    // files.
    //
    // Ensure even if this rule doesn't need to be built due to a
    // cache hit on the (empty) output of the rule, we still fetch and
    // lay out the headers so the resulting compilation database can
    // be used.
    return runtimeDeps;
  }

  class GenerateCompilationCommandsJson extends AbstractExecutionStep {

    public GenerateCompilationCommandsJson() {
      super("generate compile_commands.json");
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) {
      Iterable<CxxCompilationDatabaseEntry> entries = createEntries();
      return StepExecutionResult.of(writeOutput(entries, context));
    }

    @VisibleForTesting
    Iterable<CxxCompilationDatabaseEntry> createEntries() {
      List<CxxCompilationDatabaseEntry> entries = Lists.newArrayList();
      for (CxxPreprocessAndCompile compileRule : compileRules) {
        Optional<CxxPreprocessAndCompile> preprocessRule = Optional.absent();
        if (preprocessMode == CxxPreprocessMode.SEPARATE) {
          for (BuildRule buildRule : compileRule.getDeclaredDeps()) {
            if (CxxSourceRuleFactory.isPreprocessFlavoredBuildTarget(buildRule.getBuildTarget())) {
              preprocessRule = Optional.of((CxxPreprocessAndCompile) buildRule);
              break;
            }
          }
          if (!preprocessRule.isPresent()) {
            throw new HumanReadableException("Can't find preprocess rule for " + compileRule);
          }
        }
        entries.add(createEntry(preprocessRule, compileRule));
      }
      return entries;
    }

    private CxxCompilationDatabaseEntry createEntry(
        Optional<CxxPreprocessAndCompile> preprocessRule,
        CxxPreprocessAndCompile compileRule) {

      SourcePath inputSourcePath = preprocessRule.or(compileRule).getInput();
      ProjectFilesystem inputFilesystem = preprocessRule.or(compileRule).getProjectFilesystem();

      String fileToCompile = inputFilesystem
          .resolve(getResolver().getAbsolutePath(inputSourcePath))
          .toString();
      ImmutableList<String> arguments = compileRule.getCommand(preprocessRule);
      return CxxCompilationDatabaseEntry.of(
          inputFilesystem.getRootPath().toString(),
          fileToCompile,
          arguments);
    }

    private int writeOutput(
        Iterable<CxxCompilationDatabaseEntry> entries,
        ExecutionContext context) {
      try {
        OutputStream outputStream = getProjectFilesystem().newFileOutputStream(
            getPathToOutput());
        ObjectMapper mapper = context.getObjectMapper();
        outputStream.write(mapper.writeValueAsBytes(entries));
        outputStream.close();
      } catch (IOException e) {
        logError(e, context);
        return 1;
      }

      return 0;
    }

    private void logError(Throwable throwable, ExecutionContext context) {
      context.logError(
          throwable,
          "Failed writing to %s in %s.",
          getPathToOutput(),
          getBuildTarget());
    }
  }
}
