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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.common.SourcePathSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxConditionalLinkStrategy;
import com.facebook.buck.cxx.CxxConditionalLinkStrategyFactory;
import com.facebook.buck.cxx.CxxConditionalLinkStrategyFactoryAlwaysLink;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/** The strategy factory for {@link AppleCxxRelinkStrategy}. */
class AppleCxxRelinkStrategyFactory implements CxxConditionalLinkStrategyFactory {
  /** Returns the currently configured relinking strategy. */
  static CxxConditionalLinkStrategyFactory getConfiguredStrategy(AppleConfig appleConfig) {
    if (appleConfig.getConditionalRelinkingEnabled()) {
      return new AppleCxxRelinkStrategyFactory(appleConfig.getConditionalRelinkingFallback());
    }

    return CxxConditionalLinkStrategyFactoryAlwaysLink.FACTORY;
  }

  private final boolean fallback;

  private AppleCxxRelinkStrategyFactory(boolean fallback) {
    this.fallback = fallback;
  }

  private BuildRule createHashRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToFile) {
    return new AppleWriteFileHash(buildTarget, projectFilesystem, ruleFinder, pathToFile, true);
  }

  @Override
  public CxxConditionalLinkStrategy createStrategy(
      ActionGraphBuilder graphBuilder,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      BuildTarget target,
      ImmutableList<Arg> ldArgs,
      Linker linker,
      Path outputPath) {

    // The link strategies need to be able to distinguish between linker parameters (so can perform
    // analysis on how the linking input has changed) and all inputs (which will include additional
    // files for the linker itself and so on).
    ImmutableSet<SourcePath> inputParamFiles =
        ldArgs.stream()
            .filter(arg -> arg instanceof HasSourcePath)
            .map(arg -> ((HasSourcePath) arg).getPath())
            .collect(ImmutableSet.toImmutableSet());

    Stream.Builder<SourcePath> inputs = Stream.builder();
    BuildableSupport.deriveInputs(linker).forEach(inputs);
    for (Arg arg : ldArgs) {
      BuildableSupport.deriveInputs(arg).forEach(inputs);
    }

    ImmutableSet<SourcePath> allInputs = inputs.build().collect(ImmutableSet.toImmutableSet());
    // It's more efficient to store the non-parameter inputs rather than the other way around
    // because the relative sizes of params vs non-params for large targets (i.e., params
    // being about 99%+ of all inputs).
    ImmutableSortedSet<SourcePath> nonParameterInputs =
        allInputs.stream()
            .filter(inputPath -> !inputParamFiles.contains(inputPath))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    ImmutableMap<SourcePath, BuildTarget> inputPathToBuildTarget =
        SourcePathSupport.generateAndCheckUniquenessOfBuildTargetsForSourcePaths(
            allInputs, target.withoutFlavors(), "relink-hash-");

    ImmutableMap.Builder<SourcePath, SourcePath> inputFileHashes = ImmutableMap.builder();

    for (Map.Entry<SourcePath, BuildTarget> pathToTargetEntry : inputPathToBuildTarget.entrySet()) {
      SourcePath inputPath = pathToTargetEntry.getKey();
      BuildRule hashingRule =
          graphBuilder.computeIfAbsent(
              pathToTargetEntry.getValue(),
              hashTarget -> createHashRule(hashTarget, projectFilesystem, ruleResolver, inputPath));
      SourcePath hashPath = hashingRule.getSourcePathToOutput();
      inputFileHashes.put(inputPath, hashPath);
    }

    OutputPath relinkInfoPath =
        new PublicOutputPath(
            outputPath.resolveSibling(outputPath.getFileName() + ".relink-info.json"));

    return new AppleCxxRelinkStrategy(
        inputFileHashes.build(), nonParameterInputs, relinkInfoPath, fallback);
  }
}
