/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class MacosxFramework extends AbstractBuildable {

  private final ImmutableList<GroupedSource> srcs;
  private final ImmutableMap<SourcePath, String> perFileFlags;
  private final ImmutableSet<XcodeRuleConfiguration> configurations;
  private final ImmutableSortedSet<String> frameworks;

  public MacosxFramework(MacosxFrameworkDescription.Arg arg) {
    configurations = XcodeRuleConfiguration.fromRawJsonStructure(arg.configs);
    frameworks = Preconditions.checkNotNull(arg.frameworks);

    ImmutableList.Builder<GroupedSource> srcsBuilder = ImmutableList.builder();
    ImmutableMap.Builder<SourcePath, String> perFileFlagsBuilder = ImmutableMap.builder();
    RuleUtils.extractSourcePaths(
        srcsBuilder,
        perFileFlagsBuilder,
        arg.srcs);
    srcs = srcsBuilder.build();
    perFileFlags = perFileFlagsBuilder.build();
  }

  public ImmutableSet<XcodeRuleConfiguration> getConfigurations() {
    return configurations;
  }

  public ImmutableList<GroupedSource> getSrcs() {
    return srcs;
  }

  public ImmutableMap<SourcePath, String> getPerFileFlags() {
    return perFileFlags;
  }

  public ImmutableSortedSet<String> getFrameworks() {
    return frameworks;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(GroupedSources.sourcePaths(srcs));
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }
}
