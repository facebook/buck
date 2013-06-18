/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NativeLibraryRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * An object that represents a collection of Android NDK source code.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/jni/BUCK</code>:
 * <pre>
 * ndk_library(
 *   name = 'feed-jni',
 *   deps = [],
 *   flags = ["NDK_DEBUG=1", "V=1"],
 * )
 * </pre>
 */
public class NdkLibraryRule extends NativeLibraryRule {

  /** The directory containing the Android.mk file to use. This value includes a trailing slash. */
  private final String makefileDirectory;

  private final String buildArtifactsDirectory;
  private final ImmutableSortedSet<String> sources;
  private final ImmutableList<String> flags;

  protected NdkLibraryRule(
      BuildRuleParams buildRuleParams,
      Set<String> sources,
      List<String> flags,
      boolean isAsset) {
    super(buildRuleParams, isAsset, getLibsPath(buildRuleParams.getBuildTarget()));

    Preconditions.checkArgument(!sources.isEmpty(),
        "Must include at least one file (Android.mk?) in ndk_library rule");
    this.sources = ImmutableSortedSet.copyOf(sources);
    this.makefileDirectory = getMakefileDirectory(buildRuleParams.getBuildTarget());
    this.buildArtifactsDirectory = getBuildArtifactsDirectory(buildRuleParams.getBuildTarget());
    this.flags = ImmutableList.copyOf(flags);
  }

  private static String getMakefileDirectory(BuildTarget target) {
    return target.getBasePathWithSlash();
  }

  private static String getBuildArtifactsDirectory(BuildTarget target) {
    return String.format("%s/%s__lib%s/",
        BuckConstant.GEN_DIR,
        getMakefileDirectory(target),
        target.getShortName());
  }

  private static String getLibsPath(BuildTarget target) {
    return getBuildArtifactsDirectory(target) + "libs";
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.NDK_LIBRARY;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    // TODO(#2493457): This rule uses the ndk-build script (part of the Android NDK), so the RuleKey
    // should incorporate which version of the NDK is used.

    return super.appendToRuleKey(builder)
        .set("makefileDirectory", makefileDirectory)
        .set("buildArtifactsDirectory", buildArtifactsDirectory)
        .set("sources", sources)
        .set("flags", flags)
        .set("is_asset", isAsset());
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput() {
    return this.sources;
  }

  @Override
  protected List<Step> buildInternal(BuildContext context) throws IOException {
    return ImmutableList.of((Step)new NdkBuildStep(
        this.makefileDirectory,
        this.buildArtifactsDirectory,
        this.flags));
  }

  public static Builder newNdkLibraryRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends NativeLibraryRule.Builder<NdkLibraryRule>
      implements SrcsAttributeBuilder {
    private Set<String> sources = Sets.newHashSet();
    private ImmutableList.Builder<String> flags = ImmutableList.builder();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public NdkLibraryRule build(BuildRuleResolver ruleResolver) {
      return new NdkLibraryRule(
          createBuildRuleParams(ruleResolver),
          sources,
          flags.build(),
          this.isAsset);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    @Override
    public Builder addSrc(String source) {
      this.sources.add(source);
      return this;
    }

    public Builder addFlag(String flag) {
      this.flags.add(flag);
      return this;
    }

  }
}
