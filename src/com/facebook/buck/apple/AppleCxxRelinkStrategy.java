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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxConditionalLinkStrategy;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

/** Defines the conditional relinking strategy for Apple platforms (Mach-O executables). */
public class AppleCxxRelinkStrategy implements CxxConditionalLinkStrategy, AddsToRuleKey {
  @AddToRuleKey private final String relinkStrategyType = "apple-relink-strategy";
  @AddToRuleKey private final ImmutableMap<SourcePath, SourcePath> sourceToHashPaths;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> nonParameterInputPaths;
  @AddToRuleKey private final OutputPath relinkInfoOutputPath;

  AppleCxxRelinkStrategy(
      ImmutableMap<SourcePath, SourcePath> sourceToHashPaths,
      ImmutableSortedSet<SourcePath> nonParameterInputPaths,
      OutputPath relinkInfoPath) {
    this.sourceToHashPaths = sourceToHashPaths;
    this.nonParameterInputPaths = nonParameterInputPaths;
    this.relinkInfoOutputPath = relinkInfoPath;
  }

  private AbsPath getAbsoluteRelinkInfoPath(
      ProjectFilesystem filesystem, OutputPathResolver outputPathResolver) {
    return filesystem.resolve(outputPathResolver.resolvePath(relinkInfoOutputPath));
  }

  @Override
  public boolean isIncremental() {
    return true;
  }

  @Override
  public ImmutableList<OutputPath> getExcludedOutpathPathsFromAutomaticRemoval() {
    return ImmutableList.of(relinkInfoOutputPath);
  }

  @Override
  public ImmutableList<Step> createConditionalLinkCheckSteps(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      SourcePathResolverAdapter sourcePathResolver,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linkerCommandPrefix) {
    return ImmutableList.of(
        new AppleMachoCxxConditionalLinkCheck(
            filesystem,
            sourcePathResolver,
            sourceToHashPaths,
            argfilePath,
            filelistPath,
            getAbsoluteRelinkInfoPath(filesystem, outputPathResolver),
            skipLinkingPath,
            linkedExecutablePath,
            environment,
            linkerCommandPrefix));
  }

  @Override
  public ImmutableList<Step> createConditionalLinkWriteSteps(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      SourcePathResolverAdapter sourcePathResolver,
      AbsPath argfilePath,
      AbsPath filelistPath,
      AbsPath skipLinkingPath,
      RelPath linkedExecutablePath,
      ImmutableMap<String, String> environment,
      ImmutableList<String> linkerCommandPrefix) {
    return ImmutableList.of(
        new AppleMachoConditionalLinkWriteInfo(
            filesystem,
            sourcePathResolver,
            sourceToHashPaths,
            nonParameterInputPaths,
            argfilePath,
            filelistPath,
            getAbsoluteRelinkInfoPath(filesystem, outputPathResolver),
            skipLinkingPath,
            linkedExecutablePath,
            environment,
            linkerCommandPrefix));
  }
}
