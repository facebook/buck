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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
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
  public <A extends Arg> AndroidManifest createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ImmutableSet<Path> manifestFiles = findManifestFiles(args);

    // Filter out android_resource and android_library dependencies.
    ImmutableSortedSet<BuildRule> newDeps = FluentIterable.from(args.deps.get())
        .filter(Predicates.not(Predicates.instanceOf(AndroidResource.class)))
        .filter(Predicates.not(Predicates.instanceOf(AndroidLibrary.class)))
        .toSortedSet(BuildTarget.BUILD_TARGET_COMPARATOR);

    return new AndroidManifest(
        params.copyWithDeps(newDeps, params.getExtraDeps()),
        args.skeleton,
        manifestFiles);
  }

  public static class Arg implements ConstructorArg {
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
    return transitiveDependencyGraph.findManifestFiles();
  }
}
