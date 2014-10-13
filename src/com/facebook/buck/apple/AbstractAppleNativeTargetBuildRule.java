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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.step.Step;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A build rule that has configuration ready for Xcode-like build systems.
 */
public abstract class AbstractAppleNativeTargetBuildRule extends AbstractBuildRule {

  public static enum HeaderMapType {
    PUBLIC_HEADER_MAP("-public-headers.hmap"),
    TARGET_HEADER_MAP("-target-headers.hmap"),
    TARGET_USER_HEADER_MAP("-target-user-headers.hmap"),
    ;

    private final String suffix;

    private HeaderMapType(String suffix) {
      this.suffix = suffix;
    }
  }


  private final ImmutableMap<String, XcodeRuleConfiguration> configurations;
  private final ImmutableList<GroupedSource> srcs;
  private final ImmutableMap<SourcePath, String> perFileFlags;
  private final ImmutableSortedSet<String> frameworks;
  private final Optional<String> gid;
  private final Optional<String> headerPathPrefix;
  private final boolean useBuckHeaderMaps;

  public AbstractAppleNativeTargetBuildRule(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg arg,
      TargetSources targetSources) {
    super(params, resolver);
    configurations = XcodeRuleConfiguration.fromRawJsonStructure(arg.configs.get());
    frameworks = Preconditions.checkNotNull(arg.frameworks.get());
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

  public Optional<Path> getPathToHeaderMap(HeaderMapType headerMapType) {
    if (!getUseBuckHeaderMaps()) {
      return Optional.absent();
    }

    return Optional.of(BuildTargets.getGenPath(getBuildTarget(), "%s" + headerMapType.suffix));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("frameworks", getFrameworks())
        .set("gid", gid)
        .set("headerPathPrefix", headerPathPrefix)
        .set("useBuckHeaderMaps", useBuckHeaderMaps);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(
        GroupedSources.sourcePaths(srcs));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // TODO(user) This is just a placeholder. We'll be replacing this as we move
    // to support CxxLibrary and friends.
    return ImmutableList.of();
  }

  /**
   * Format string used for the filename of the Path returned by getPathToOutputFile().
   */
  protected String getOutputFileNameFormat() {
    return "%s";
  }

  @Override
  public Path getPathToOutputFile() {
    BuildTarget target = getBuildTarget();

    if (!target.getFlavors().contains(Flavor.DEFAULT)) {
      // TODO(grp): Consider putting this path format logic in BuildTargets.getBinPath() directly.
      return Paths.get(String.format("%s/%s/%s/" + getOutputFileNameFormat(),
              BuckConstant.BIN_DIR,
              target.getBasePathWithSlash(),
              target.getFlavorPostfix(),
              target.getShortNameOnly()));
    } else {
      return BuildTargets.getBinPath(target, getOutputFileNameFormat());
    }
  }
}
