/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidManifestDescription implements Description<AndroidManifestDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("android_manifest");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public Buildable createBuildable(BuildRuleParams params, Arg args) {
    ImmutableSet<Path> manifestFiles = findManifestFiles(args);

    return new AndroidManifest(params.getBuildTarget(), args.skeleton, manifestFiles);
  }

  public static class Arg {
    public SourcePath skeleton;

    /**
     * A collection of dependencies that includes android_library rules. The manifest files of the
     * android_library rules will be filtered out to become dependent source files for the
     * {@link AndroidManifest}.
     */
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

  @VisibleForTesting
  static ImmutableSet<Path> findManifestFiles(Arg args) {
    AndroidTransitiveDependencyGraph transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(args.deps.get());
    AndroidTransitiveDependencies transitiveDependencies =
        transitiveDependencyGraph.findDependencies();

    return transitiveDependencies.manifestFiles;
  }
}
