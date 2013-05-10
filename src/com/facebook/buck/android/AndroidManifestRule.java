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

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.AndroidResourceRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AndroidManifestRule extends AbstractCachingBuildRule {

  private final Optional<String> manifestFile;
  private final Optional<String> skeletonFile;
  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;
  private final ImmutableSet<BuildRule> buildRulesToExcludeFromDex;

  protected AndroidManifestRule(BuildRuleParams buildRuleParams,
                                Optional<String> skeletonFile,
                                Optional<String> manifestFile,
                                Set<BuildRule> buildRulesToExcludeFromDex) {
    super(buildRuleParams);
    this.manifestFile = manifestFile;
    this.skeletonFile = skeletonFile;
    this.buildRulesToExcludeFromDex = ImmutableSet.copyOf(buildRulesToExcludeFromDex);
    this.transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(this, this.buildRulesToExcludeFromDex);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_MANIFEST;
  }

  @Override
  protected List<String> getInputsToCompareToOutput(BuildContext context) {
    ImmutableList.Builder<String> inputsToConsiderForCachingPurposes = ImmutableList.builder();
    if (skeletonFile.isPresent() && manifestFile.isPresent()) {
      inputsToConsiderForCachingPurposes.add(skeletonFile.get());
      inputsToConsiderForCachingPurposes.add(manifestFile.get());
    }
    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  protected List<Step> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    AndroidTransitiveDependencies transitiveDependencies =
        transitiveDependencyGraph.findDependencies(getAndroidResourceDepsInternal(
            context.getDependencyGraph()),
            Optional.of(context));

    if (skeletonFile.isPresent() && manifestFile.isPresent()) {
      commands.add(new GenerateManifestStep(
          skeletonFile.get(),
          manifestFile.get(),
          transitiveDependencies.manifestFiles));
    }
    return commands.build();
  }

  public static Builder newManifestMergeRuleBuilder() {
    return new Builder();
  }

  /**
   * @return a list of {@link com.facebook.buck.rules.AndroidResourceRule}s that should be passed,
   * in order, to {@code aapt} when generating the {@code R.java} files for this APK.
   */
  private ImmutableList<AndroidResourceRule> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    return AndroidResourceRule.getAndroidResourceDeps(this, graph);
  }


  public static class Builder extends AbstractBuildRuleBuilder {

    protected Optional<String> manifestFile;
    protected Optional<String> skeletonFile;
    private Set<String> buildRulesToExcludeFromDex = Sets.newHashSet();

    @Override
    public AndroidManifestRule build(Map<String, BuildRule> buildRuleIndex) {
      boolean allowNonExistentRule =
        false;

      return new AndroidManifestRule(createBuildRuleParams(buildRuleIndex),
          skeletonFile,
          manifestFile,
          getBuildTargetsAsBuildRules(buildRuleIndex,
              buildRulesToExcludeFromDex,
              allowNonExistentRule));
    }

    public Builder setManifestFile(String manifestFile) {
      this.manifestFile = Optional.of(manifestFile);
      return this;
    }

    public Builder setSkeletonFile(String skeletonFile) {
      this.skeletonFile = Optional.of(skeletonFile);
      return this;
    }

    public Builder addBuildRuleToExcludeFromDex(String entry) {
      this.buildRulesToExcludeFromDex.add(entry);
      return this;
    }

  }
}
