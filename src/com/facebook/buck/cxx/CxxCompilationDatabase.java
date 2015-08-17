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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class CxxCompilationDatabase extends AbstractBuildRule implements HasPostBuildSteps {
  public static final Flavor COMPILATION_DATABASE = ImmutableFlavor.of("compilation-database");

  private final CxxPreprocessMode preprocessMode;
  private final ImmutableSortedSet<CxxPreprocessAndCompile> compileRules;
  private final Path outputJsonFile;

  public static CxxCompilationDatabase createCompilationDatabase(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      CxxPreprocessMode preprocessMode,
      Iterable<CxxPreprocessAndCompile> compileAndPreprocessRules) {
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
            Suppliers.ofInstance(params.getExtraDeps())),
        pathResolver,
        compileRules.build(),
        preprocessMode);
  }

  static BuildRuleParams paramsWithoutCompilationDatabaseFlavor(BuildRuleParams params) {
    Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
    Preconditions.checkArgument(flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE));
    flavors.remove(CxxCompilationDatabase.COMPILATION_DATABASE);
    BuildTarget target = BuildTarget
        .builder(params.getBuildTarget().getUnflavoredBuildTarget())
        .addAllFlavors(flavors)
        .build();

    return params.copyWithChanges(
        target,
        Suppliers.ofInstance(params.getDeclaredDeps()),
        Suppliers.ofInstance(params.getExtraDeps()));
  }

  CxxCompilationDatabase(
      BuildRuleParams buildRuleParams,
      SourcePathResolver pathResolver,
      ImmutableSortedSet<CxxPreprocessAndCompile> compileRules,
      CxxPreprocessMode preprocessMode) {
    super(buildRuleParams, pathResolver);
    this.compileRules = compileRules;
    this.preprocessMode = preprocessMode;
    this.outputJsonFile = BuildTargets.getGenPath(buildRuleParams.getBuildTarget(), "__%s.json");
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
    steps.add(new MkdirStep(outputJsonFile.getParent()));
    steps.add(new GenerateCompilationCommandsJson());
    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return outputJsonFile;
  }

  class GenerateCompilationCommandsJson extends AbstractExecutionStep {

    public GenerateCompilationCommandsJson() {
      super("generate compile_commands.json");
    }

    @Override
    public int execute(ExecutionContext context) {
      Iterable<CxxCompilationDatabaseEntry> entries = createEntries();
      return writeOutput(entries, context);
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
          .resolve(getResolver().getPath(inputSourcePath))
          .toString();
      ImmutableList<String> args = preprocessRule.isPresent() ?
          compileRule.getCompileCommandCombinedWithPreprocessBuildRule(preprocessRule.get()) :
          compileRule.getCommand();
      return new CxxCompilationDatabaseEntry(
          /* directory */ getProjectFilesystem().resolve(getBuildTarget().getBasePath()).toString(),
          fileToCompile,
          args);
    }

    private int writeOutput(
        Iterable<CxxCompilationDatabaseEntry> entries,
        ExecutionContext context) {
      Gson gson = new Gson();
      try {
        OutputStream outputStream = getProjectFilesystem().newFileOutputStream(
            getPathToOutput());
        outputStream.write(gson.toJson(entries).getBytes());
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
