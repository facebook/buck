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

package com.facebook.buck.jvm.java.autodeps;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/** A BuildRule for extracting java symbols for java autodepsk */
final class JavaSymbolsRule extends AbstractBuildRule implements InitializableFromDisk<Symbols> {

  interface SymbolsFinder extends AddsToRuleKey {
    Symbols extractSymbols() throws IOException;
  }

  public static final Flavor JAVA_SYMBOLS = InternalFlavor.of("java_symbols");

  @AddToRuleKey private final SymbolsFinder symbolsFinder;

  private final Path outputPath;
  private final BuildOutputInitializer<Symbols> outputInitializer;

  JavaSymbolsRule(
      BuildTarget javaLibraryBuildTarget,
      SymbolsFinder symbolsFinder,
      ProjectFilesystem projectFilesystem) {
    super(javaLibraryBuildTarget.withFlavors(JAVA_SYMBOLS), projectFilesystem);
    this.symbolsFinder = symbolsFinder;
    this.outputPath =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__.json");
    this.outputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  public Symbols getFeatures() {
    return outputInitializer.getBuildOutput();
  }

  @Override
  public Symbols initializeFromDisk() throws IOException {
    List<String> lines = getProjectFilesystem().readLines(outputPath);
    Preconditions.checkArgument(lines.size() == 1, "Should be one line of JSON: %s", lines);
    return ObjectMappers.readValue(lines.get(0), Symbols.class);
  }

  @Override
  public BuildOutputInitializer<Symbols> getBuildOutputInitializer() {
    return outputInitializer;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Step mkdirStep =
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath.getParent()));
    Step extractSymbolsStep =
        new AbstractExecutionStep("java-symbols") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            try (OutputStream output = getProjectFilesystem().newFileOutputStream(outputPath)) {
              ObjectMappers.WRITER.writeValue(output, symbolsFinder.extractSymbols());
            }

            return StepExecutionResults.SUCCESS;
          }
        };

    return ImmutableList.of(mkdirStep, extractSymbolsStep);
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }

  @Override
  public boolean isCacheable() {
    return true;
  }
}
