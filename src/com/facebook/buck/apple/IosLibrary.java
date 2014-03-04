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
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

public class IosLibrary extends AbstractBuildable {

  private final ImmutableSet<XcodeRuleConfiguration> configurations;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> headers;
  private final ImmutableSortedSet<String> frameworks;
  private final ImmutableMap<SourcePath, String> perFileCompilerFlags;
  private final ImmutableMap<SourcePath, HeaderVisibility> perHeaderVisibility;
  private final ImmutableList<GroupedSource> groupedSrcs;
  private final ImmutableList<GroupedSource> groupedHeaders;

  public IosLibrary(IosLibraryDescription.Arg arg) {
    configurations = XcodeRuleConfiguration.fromRawJsonStructure(arg.configs);
    frameworks = Preconditions.checkNotNull(arg.frameworks);

    ImmutableSortedSet.Builder<SourcePath> srcsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlagsBuilder = ImmutableMap.builder();
    ImmutableList.Builder<GroupedSource> groupedSourcesBuilder = ImmutableList.builder();
    RuleUtils.extractSourcePaths(
        srcsBuilder,
        perFileCompileFlagsBuilder,
        groupedSourcesBuilder,
        arg.srcs);
    srcs = srcsBuilder.build();
    perFileCompilerFlags = perFileCompileFlagsBuilder.build();
    groupedSrcs = groupedSourcesBuilder.build();

    ImmutableSortedSet.Builder<SourcePath> headersBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, HeaderVisibility> perHeaderVisibilityBuilder =
      ImmutableMap.builder();
    ImmutableList.Builder<GroupedSource> groupedHeadersBuilder = ImmutableList.builder();
    RuleUtils.extractHeaderPaths(
        headersBuilder,
        perHeaderVisibilityBuilder,
        groupedHeadersBuilder,
        arg.headers);
    headers = headersBuilder.build();
    perHeaderVisibility = perHeaderVisibilityBuilder.build();
    groupedHeaders = groupedHeadersBuilder.build();
  }

  public ImmutableSet<XcodeRuleConfiguration> getConfigurations() {
    return configurations;
  }

  public ImmutableSortedSet<SourcePath> getSrcs() {
    return srcs;
  }

  public ImmutableSortedSet<SourcePath> getHeaders() {
    return headers;
  }

  public ImmutableSortedSet<String> getFrameworks() {
    return frameworks;
  }

  public ImmutableMap<SourcePath, String> getPerFileCompilerFlags() {
    return perFileCompilerFlags;
  }

  public ImmutableMap<SourcePath, HeaderVisibility> getPerHeaderVisibility() {
    return perHeaderVisibility;
  }

  public ImmutableList<GroupedSource> getGroupedSrcs() {
    return groupedSrcs;
  }

  public ImmutableList<GroupedSource> getGroupedHeaders() {
    return groupedHeaders;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Iterables.concat(srcs, headers));
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }
}
