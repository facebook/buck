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

package com.facebook.buck.cxx;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * This strategy behaves as a nop / default: everything will always be linked, as if there's no
 * conditional link strategy.
 */
public class CxxConditionalLinkStrategyAlwaysLink
    implements CxxConditionalLinkStrategy, AddsToRuleKey {
  public static final CxxConditionalLinkStrategy STRATEGY =
      new CxxConditionalLinkStrategyAlwaysLink();

  @AddToRuleKey private final String relinkStrategyType = "cxx-always-link-strategy";

  @Override
  public boolean isIncremental() {
    return false;
  }

  @Override
  public ImmutableList<OutputPath> getExcludedOutpathPathsFromAutomaticRemoval() {
    return ImmutableList.of();
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
    return ImmutableList.of();
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
    return ImmutableList.of();
  }
}
