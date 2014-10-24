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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
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

  private final ImmutableSortedMap<String, XcodeRuleConfiguration> configurations;
  private final ImmutableList<GroupedSource> srcs;
  private final ImmutableSortedMap<SourcePath, String> perFileFlags;
  private final ImmutableSortedSet<String> frameworks;
  private final Optional<String> gid;
  private final Optional<String> headerPathPrefix;
  private final boolean useBuckHeaderMaps;
  private final Optional<SourcePath> prefixHeader;

  public AbstractAppleNativeTargetBuildRule(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg arg,
      TargetSources targetSources) {
    super(params, resolver);
    configurations = arg.configs.get();
    srcs = targetSources.srcs;
    perFileFlags = targetSources.perFileFlags;
    frameworks = arg.frameworks.get();
    gid = arg.gid;
    headerPathPrefix = arg.headerPathPrefix;
    useBuckHeaderMaps = arg.useBuckHeaderMaps.or(false);
    prefixHeader = arg.prefixHeader;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    SrcsAndGroupNames srcsAndGroupNames = collectSrcsAndGroupNames();
    return getResolver().filterInputsToCompareToOutput(srcsAndGroupNames.srcs);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(final RuleKey.Builder builder) {
    SrcsAndGroupNames srcsAndGroupNames = collectSrcsAndGroupNames();
    return builder
        .set("configurationsKeys", configurations.keySet())
        // TODO(mbolin): Include configurationsValues. Requires finding a way to encode an
        // XcodeRuleConfiguration in a RuleKey.Builder.
        // .set("configurationsValues", configurations.values())
        .setSourcePaths("srcsSourcePaths", srcsAndGroupNames.srcs)
        .set("srcsGroupNames", srcsAndGroupNames.groupNames)
        .setSourcePaths("perFileFlagsKeys", perFileFlags.keySet())
        .set("perFileFlagsValues", ImmutableList.copyOf(perFileFlags.values()))
        .set("frameworks", getFrameworks())
        .set("gid", gid)
        .set("headerPathPrefix", headerPathPrefix)
        .set("useBuckHeaderMaps", useBuckHeaderMaps)
        .setReflectively("prefixHeader", prefixHeader);
  }

  private SrcsAndGroupNames collectSrcsAndGroupNames() {
    final ImmutableSortedSet.Builder<SourcePath> groupSrcs = ImmutableSortedSet.naturalOrder();
    final ImmutableList.Builder<String> groupNames = ImmutableList.builder();

    // Create a synthetic parent GroupedSource named "" and use it as the starting point of the
    // visitor to add all of the srcs information to the RuleKey.Builder.
    GroupedSource.ofSourceGroup(/* sourceGroupName */ "", srcs).visit(new GroupedSource.Visitor() {

      @Override
      public void visitSourcePath(SourcePath sourcePath) {
        groupSrcs.add(sourcePath);
      }

      @Override
      public void visitSourceGroup(String sourceGroupName) {
        groupNames.add(sourceGroupName);
      }
    });

    return new SrcsAndGroupNames(groupSrcs.build(), groupNames.build());
  }

  private static class SrcsAndGroupNames {
    private final ImmutableSortedSet<SourcePath> srcs;
    private final ImmutableList<String> groupNames;
    public SrcsAndGroupNames(
        ImmutableSortedSet<SourcePath> srcs,
        ImmutableList<String> groupNames) {
      this.srcs = srcs;
      this.groupNames = groupNames;
    }
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
   * @return A map where each entry is a mapping from a source file to a space-delimited string that
   *     serves as a list of per-file build flags for that file, e.g., {@code "-fobjc-arc"} or
   *     {@code "-fobjc-arc -DOS_OBJECT_USE_OBJC=0"}.
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

  /** @return A {@code .pch} to pass to {@code -include}. */
  public Optional<SourcePath> getPrefixHeader() {
    return prefixHeader;
  }

  public Optional<Path> getPathToHeaderMap(HeaderMapType headerMapType) {
    if (!getUseBuckHeaderMaps()) {
      return Optional.absent();
    }

    return Optional.of(BuildTargets.getGenPath(getBuildTarget(), "%s" + headerMapType.suffix));
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
