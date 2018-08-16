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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
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
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Iterable<CxxPreprocessAndCompile> compileAndPreprocessRules) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<CxxPreprocessAndCompile> compileRules =
        ImmutableSortedSet.naturalOrder();
    for (CxxPreprocessAndCompile compileRule : compileAndPreprocessRules) {
      compileRules.add(compileRule);
      deps.addAll(compileRule.getBuildDeps());
    }

    return new CxxCompilationDatabase(
        buildTarget, projectFilesystem, compileRules.build(), deps.build());
  }

  CxxCompilationDatabase(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableSortedSet<CxxPreprocessAndCompile> compileRules,
      ImmutableSortedSet<BuildRule> runtimeDeps) {
    super(buildTarget, projectFilesystem);
    LOG.debug("Creating compilation database %s with runtime deps %s", buildTarget, runtimeDeps);
    this.compileRules = compileRules;
    this.outputJsonFile =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(),
            buildTarget,
            Paths.get("__%s", "compile_commands.json").toString());
    this.runtimeDeps = runtimeDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                outputJsonFile.getParent())));
    steps.add(
        new GenerateCompilationCommandsJson(
            context, context.getSourcePathResolver().getRelativePath(getSourcePathToOutput())));
    return steps.build();
  }

  @Override
  public boolean isCacheable() {
    // We don't want to cache the output of this rule because it contains absolute paths.
    return false;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputJsonFile);
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
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

    private final BuildContext context;
    private final Path outputRelativePath;

    public GenerateCompilationCommandsJson(BuildContext context, Path outputRelativePath) {
      super("generate compile_commands.json");
      this.context = context;
      this.outputRelativePath = outputRelativePath;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      try (OutputStream outputStream =
          getProjectFilesystem().newFileOutputStream(outputRelativePath)) {
        try (JsonGenerator jsonGen = ObjectMappers.createGenerator(outputStream)) {
          jsonGen.writeStartArray();
          for (Iterator<CxxCompilationDatabaseEntry> entry = createEntries().iterator();
              entry.hasNext(); ) {
            jsonGen.writeObject(entry.next());
          }
          jsonGen.writeEndArray();
        }
      }

      return StepExecutionResult.of(0);
    }

    @VisibleForTesting
    Stream<CxxCompilationDatabaseEntry> createEntries() {
      return compileRules.stream().map(compileRule -> createEntry(compileRule));
    }

    private CxxCompilationDatabaseEntry createEntry(CxxPreprocessAndCompile compileRule) {

      SourcePath inputSourcePath = compileRule.getInput();
      ProjectFilesystem inputFilesystem = compileRule.getProjectFilesystem();

      String fileToCompile =
          inputFilesystem
              .resolve(context.getSourcePathResolver().getAbsolutePath(inputSourcePath))
              .toString();
      ImmutableList<String> arguments = compileRule.getCommand(context);
      return CxxCompilationDatabaseEntry.of(
          inputFilesystem.getRootPath().toString(), fileToCompile, arguments);
    }
  }
}
