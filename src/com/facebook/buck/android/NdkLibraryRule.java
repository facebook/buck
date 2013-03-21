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
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.AbstractCachingBuildRuleBuilder;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CachingBuildRuleParams;
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
import java.util.Map;
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
public class NdkLibraryRule extends AbstractCachingBuildRule {

  /** The directory containing the Android.mk file to use. This value includes a trailing slash. */
  private final String makefileDirectory;

  private final String buildArtifactsDirectory;
  private final ImmutableSortedSet<String> sources;
  private final ImmutableList<String> flags;

  protected NdkLibraryRule(
      CachingBuildRuleParams cachingBuildRuleParams,
      Set<String> sources,
      List<String> flags) {
    super(cachingBuildRuleParams);
    Preconditions.checkArgument(!sources.isEmpty(),
        "Must include at least one file (Android.mk?) in ndk_library rule");
    this.sources = ImmutableSortedSet.copyOf(sources);

    String basePathWithSlash = cachingBuildRuleParams.getBuildTarget().getBasePathWithSlash();
    Preconditions.checkArgument(basePathWithSlash.endsWith("/"));
    this.makefileDirectory = basePathWithSlash;
    this.buildArtifactsDirectory =
        String.format("%s/%s", BuckConstant.GEN_DIR, basePathWithSlash);
    this.flags = ImmutableList.copyOf(flags);
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
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("makefileDirectory", makefileDirectory)
        .set("buildArtifactsDirectory", buildArtifactsDirectory)
        .set("sources", sources)
        .set("flags", flags);
  }

  /**
   * The directory containing all shared objects built by this rule. This
   * value does *not* include a trailing slash.
   */
  public String getLibraryPath() {
    return this.buildArtifactsDirectory + "libs";
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    return this.sources;
  }

  @Override
  protected List<Step> buildInternal(BuildContext context) throws IOException {
    return ImmutableList.of((Step)new NdkBuildStep(
        this.makefileDirectory,
        this.buildArtifactsDirectory,
        this.flags));
  }

  public static Builder newNdkLibraryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractCachingBuildRuleBuilder implements
      SrcsAttributeBuilder {
    private Set<String> sources = Sets.newHashSet();
    private ImmutableList.Builder<String> flags = ImmutableList.builder();

    private Builder() {}

    @Override
    public NdkLibraryRule build(Map<String, BuildRule> buildRuleIndex) {
      return new NdkLibraryRule(
          createCachingBuildRuleParams(buildRuleIndex),
          sources,
          flags.build());
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

    @Override
    public Builder setArtifactCache(ArtifactCache artifactCache) {
      super.setArtifactCache(artifactCache);
      return this;
    }

    public Builder addFlag(String flag) {
      this.flags.add(flag);
      return this;
    }
  }
}
