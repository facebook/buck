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
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * {@link AndroidManifest} is a {@link Buildable} that can generate an Android manifest from a
 * skeleton manifest and the library manifests from its dependencies.
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
public class AndroidManifest extends AbstractBuildable {

  private final BuildTarget buildTarget;
  private final SourcePath skeletonFile;

  protected AndroidManifest(BuildTarget buildTarget, SourcePath skeletonFile) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.skeletonFile = Preconditions.checkNotNull(skeletonFile);
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Collections.singleton(skeletonFile));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.
        set("skeleton", skeletonFile.asReference());
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    DependencyGraph graph = context.getDependencyGraph();
    ImmutableList<HasAndroidResourceDeps> depsWithAndroidResources = getAndroidResourceDeps(graph);

    AndroidTransitiveDependencyGraph transitiveDependencyGraph =
        AndroidTransitiveDependencyGraph.createForAndroidManifest(this, graph);
    AndroidTransitiveDependencies transitiveDependencies =
        transitiveDependencyGraph.findDependencies(depsWithAndroidResources);

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.add(new GenerateManifestStep(
        skeletonFile.resolve(context).toString(),
        transitiveDependencies.manifestFiles,
        getPathToOutputFile()));

    return commands.build();
  }

  @Override
  public String getPathToOutputFile() {
    BuildTarget target = buildTarget;
    return String.format("%s/%sAndroidManifest__%s__.xml",
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortName());
  }

  /**
   * @return a list of {@link AndroidResourceRule}s that should be passed,
   * in order, to {@code aapt} when generating the {@code R.java} files for this APK.
   */
  private ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps(DependencyGraph graph) {
    BuildRule self = graph.findBuildRuleByTarget(buildTarget);
    return UberRDotJavaUtil.getAndroidResourceDeps(self);
  }
}
