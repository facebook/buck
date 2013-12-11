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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

  /** These must be sorted so {@link #getInputsToCompareToOutput} returns a consistent value. */
  private final ImmutableSortedSet<String> manifestFiles;

  private final Path pathToOutputFile;

  protected AndroidManifest(BuildTarget buildTarget,
      SourcePath skeletonFile,
      Set<String> manifestFiles) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.skeletonFile = Preconditions.checkNotNull(skeletonFile);
    this.manifestFiles = ImmutableSortedSet.copyOf(manifestFiles);
    this.pathToOutputFile = Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        "AndroidManifest__" + buildTarget.getShortName() + "__.xml");
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    Iterable<String> sourcePaths = SourcePaths.filterInputsToCompareToOutput(Collections.singleton(
        skeletonFile));
    return Iterables.concat(sourcePaths, manifestFiles);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("skeleton", skeletonFile.asReference())
        .set("manifestFiles", manifestFiles);
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public SourcePath getSkeletonFile() {
    return skeletonFile;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Clear out the old file, if it exists.
    commands.add(new RmStep(pathToOutputFile,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false));

    // Make sure the directory for the output file exists.
    commands.add(new MkdirStep(pathToOutputFile.getParent()));

    commands.add(new GenerateManifestStep(
        skeletonFile.resolve(context).toString(),
        manifestFiles,
        getPathToOutputFile()));

    return commands.build();
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutputFile.toString();
  }


  @Override
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(BuildRuleResolver ruleResolver) {
    SourcePath skeletonFile = getSkeletonFile();
    if (skeletonFile instanceof BuildTargetSourcePath) {
      BuildTarget skeletonTarget = ((BuildTargetSourcePath) skeletonFile).getTarget();
      BuildRule skeletonRule = ruleResolver.get(skeletonTarget);
      return ImmutableSortedSet.<BuildRule>of(skeletonRule);
    } else {
      return ImmutableSortedSet.<BuildRule>of();
    }
  }

}
