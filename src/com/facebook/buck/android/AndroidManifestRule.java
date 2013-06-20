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
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;

/**
 * The build rule generates the application's manifest file from the skeleton manifest and
 * the library manifests from its dependencies.
 * <pre>
 * android_manifest(
 *   name = 'sample_app',
 *   skeleton = 'AndroidManifestSkeleton.xml',
 *   manifest = genfile('MergedAndroidManifest.xml')
 *   deps = [
 *     ':sample_manifest',
 *     # Additional dependent android_library rules would be listed here, as well.
 *   ],
 * )
 * </pre>
 */
public class AndroidManifestRule extends AbstractCachingBuildRule {

  private final String manifestFile;
  private final String skeletonFile;
  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;

  protected AndroidManifestRule(BuildRuleParams buildRuleParams,
                                String skeletonFile,
                                String manifestFile) {
    super(buildRuleParams);
    this.manifestFile = Preconditions.checkNotNull(manifestFile);
    this.skeletonFile = Preconditions.checkNotNull(skeletonFile);
    this.transitiveDependencyGraph = new AndroidTransitiveDependencyGraph(this);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_MANIFEST;
  }

  @Override
  protected List<String> getInputsToCompareToOutput() {
    ImmutableList.Builder<String> inputsToConsiderForCachingPurposes = ImmutableList.builder();
    inputsToConsiderForCachingPurposes.add(skeletonFile);
    inputsToConsiderForCachingPurposes.add(manifestFile);
    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  protected List<Step> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    AndroidTransitiveDependencies transitiveDependencies =
        transitiveDependencyGraph.findDependencies(getAndroidResourceDepsInternal(
            context.getDependencyGraph()));

    commands.add(new GenerateManifestStep(
        skeletonFile,
        manifestFile,
        transitiveDependencies.manifestFiles));

    return commands.build();
  }

  public static Builder newManifestMergeRuleBuilder() {
    return new Builder();
  }

  /**
   * @return a list of {@link AndroidResourceRule}s that should be passed,
   * in order, to {@code aapt} when generating the {@code R.java} files for this APK.
   */
  private ImmutableList<HasAndroidResourceDeps> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    return UberRDotJavaUtil.getAndroidResourceDeps(this, graph);
  }


  public static class Builder extends AbstractBuildRuleBuilder<AndroidManifestRule> {

    protected String manifestFile;
    protected String skeletonFile;

    @Override
    public AndroidManifestRule build(BuildRuleResolver ruleResolver) {
      return new AndroidManifestRule(
          createBuildRuleParams(ruleResolver),
          skeletonFile,
          manifestFile);
    }

    public Builder setManifestFile(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }

    public Builder setSkeletonFile(String skeletonFile) {
      this.skeletonFile = skeletonFile;
      return this;
    }

  }
}
