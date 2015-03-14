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
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Collections;

public class AndroidManifestDescription implements Description<AndroidManifestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_manifest");

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
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    AndroidTransitiveDependencyGraph transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(resolver.getAllRules(args.deps.get()));
    ImmutableSet<SourcePath> manifestFiles = transitiveDependencyGraph.findManifestFiles();

    // The only rules that need to be built before this AndroidManifest are those
    // responsible for generating the AndroidManifest.xml files in the manifestFiles set (and
    // possibly the skeleton).
    //
    // If the skeleton is a BuildTargetSourcePath, then its build rule must also be in the deps.
    // The skeleton does not appear to be in either params.getDeclaredDeps() or
    // params.getExtraDeps(), even though the type of Arg.skeleton is SourcePath.
    // TODO(simons): t4744625 This should happen automagically.
    ImmutableSortedSet<BuildRule> newDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(
            pathResolver.filterBuildRuleInputs(
                Sets.union(manifestFiles, Collections.singleton(args.skeleton))))
        .build();

    return new AndroidManifest(
        params.copyWithDeps(
            Suppliers.ofInstance(newDeps),
            Suppliers.ofInstance(params.getExtraDeps())),
        pathResolver,
        args.skeleton,
        manifestFiles);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public SourcePath skeleton;

    /**
     * A collection of dependencies that includes android_library rules. The manifest files of the
     * android_library rules will be filtered out to become dependent source files for the
     * {@link AndroidManifest}.
     */
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
