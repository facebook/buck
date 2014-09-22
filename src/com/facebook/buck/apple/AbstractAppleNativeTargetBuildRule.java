/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cxx.AbstractNativeBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * A build rule that has configuration ready for Xcode-like build systems.
 */
public abstract class AbstractAppleNativeTargetBuildRule extends AbstractNativeBuildRule {

  private final ImmutableMap<String, XcodeRuleConfiguration> configurations;
  private final ImmutableList<GroupedSource> srcs;
  private final ImmutableMap<SourcePath, String> perFileFlags;
  private final ImmutableSortedSet<String> frameworks;
  private final ImmutableSortedSet<String> weakFrameworks;
  private final Optional<String> gid;
  private final Optional<String> headerPathPrefix;
  private final boolean useBuckHeaderMaps;

  public AbstractAppleNativeTargetBuildRule(
      BuildRuleParams params,
      AppleNativeTargetDescriptionArg arg,
      TargetSources targetSources) {
    super(params, targetSources.srcPaths, targetSources.headerPaths, targetSources.perFileFlags);
    configurations = XcodeRuleConfiguration.fromRawJsonStructure(arg.configs.get());
    frameworks = Preconditions.checkNotNull(arg.frameworks.get());
    weakFrameworks = Preconditions.checkNotNull(arg.weakFrameworks.get());
    srcs = Preconditions.checkNotNull(targetSources.srcs);
    perFileFlags = Preconditions.checkNotNull(targetSources.perFileFlags);
    gid = Preconditions.checkNotNull(arg.gid);
    headerPathPrefix = Preconditions.checkNotNull(arg.headerPathPrefix);
    useBuckHeaderMaps = Preconditions.checkNotNull(arg.useBuckHeaderMaps).or(false);
  }

  /**
   * Returns a set of Xcode configuration rules.
   */
  public ImmutableMap<String, XcodeRuleConfiguration> getConfigurations() {
    return configurations;
  }

  /**
   * Returns a list of sources, potentially grouped for display in Xcode.
   */
  public ImmutableList<GroupedSource> getSrcs() {
    return srcs;
  }

  /**
   * Returns a list of per-file build flags, e.g. -fobjc-arc.
   */
  public ImmutableMap<SourcePath, String> getPerFileFlags() {
    return perFileFlags;
  }

  /**
   * Returns the set of frameworks to link with the target.
   */
  public ImmutableSortedSet<String> getFrameworks() {
    return frameworks;
  }

  /**
   * Returns the set of frameworks to weak link with the target.
   */
  public ImmutableSortedSet<String> getWeakFrameworks() {
    return weakFrameworks;
  }

  /**
   * Returns an optional GID to be used for the target, if present.
   */
  public Optional<String> getGid() {
    return gid;
  }

  /**
   * @return An optional prefix to be used instead of the target name when exposing library headers.
   */
  public Optional<String> getHeaderPathPrefix() { return headerPathPrefix; }

  /**
   * @return A boolean whether Buck should generate header maps for this project.
   */
  public boolean getUseBuckHeaderMaps() { return useBuckHeaderMaps; }

  @Override
  protected String getCompiler() {
    return "clang";
  }
}
