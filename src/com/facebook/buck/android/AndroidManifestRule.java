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
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;

/**
 * {@link AndroidManifestRule} is a build rule that can generate an Android manifest from a skeleton
 * manifest and the library manifests from its dependencies.
 * <pre>
 * android_manifest(
 *   name = 'my_manifest',
 *   skeleton = 'AndroidManifestSkeleton.xml',
 *   deps = [
 *     ':sample_manifest',
 *     # Additional dependent android_resource and android_library rules would be listed here,
 *     # as well.
 *   ],
 * )
 * </pre>
 * This will produce a genfile that will be parameterized by the name of the
 * {@code android_manifest} rule. This can be used as follows:
 * <pre>
 * android_binary(
 *   name = 'my_app',
 *   manifest = genfile('AndroidManifest__manifest__.xml'),
 *   deps = [
 *     ':my_manifest',
 *   ],
 * )
 * </pre>
 */
public class AndroidManifestRule extends DoNotUseAbstractBuildable {

  private final String skeletonFile;
  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;

  protected AndroidManifestRule(BuildRuleParams buildRuleParams, String skeletonFile) {
    super(buildRuleParams);
    this.skeletonFile = Preconditions.checkNotNull(skeletonFile);
    this.transitiveDependencyGraph = new AndroidTransitiveDependencyGraph(this);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_MANIFEST;
  }

  @Override
  public List<String> getInputsToCompareToOutput() {
    ImmutableList.Builder<String> inputsToConsiderForCachingPurposes = ImmutableList.builder();
    // manifestFile is an *output*, so it should be omitted here.
    inputsToConsiderForCachingPurposes.add(skeletonFile);
    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context) throws IOException {
    ImmutableList<HasAndroidResourceDeps> depsWithAndroidResources = getAndroidResourceDeps(
        context.getDependencyGraph());
    AndroidTransitiveDependencies transitiveDependencies =
        transitiveDependencyGraph.findDependencies(depsWithAndroidResources);

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.add(new GenerateManifestStep(
        skeletonFile,
        transitiveDependencies.manifestFiles,
        getPathToOutputFile()));

    return commands.build();
  }

  @Override
  public String getPathToOutputFile() {
    BuildTarget target = getBuildTarget();
    return String.format("%s/%sAndroidManifest__%s__.xml",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName());
  }

  /**
   * @return a list of {@link AndroidResourceRule}s that should be passed,
   * in order, to {@code aapt} when generating the {@code R.java} files for this APK.
   */
  private ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(
      DependencyGraph graph) {
    return UberRDotJavaUtil.getAndroidResourceDeps(this, graph);
  }

  public static Builder newManifestMergeRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<AndroidManifestRule> {

    protected String skeletonFile;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public AndroidManifestRule build(BuildRuleResolver ruleResolver) {
      return new AndroidManifestRule(createBuildRuleParams(ruleResolver), skeletonFile);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setSkeletonFile(String skeletonFile) {
      this.skeletonFile = skeletonFile;
      return this;
    }
  }
}
