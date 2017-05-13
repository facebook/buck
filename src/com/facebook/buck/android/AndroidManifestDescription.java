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
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.Collections;
import org.immutables.value.Value;

public class AndroidManifestDescription implements Description<AndroidManifestDescriptionArg> {

  @Override
  public Class<AndroidManifestDescriptionArg> getConstructorArgType() {
    return AndroidManifestDescriptionArg.class;
  }

  @Override
  public AndroidManifest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidManifestDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    AndroidTransitiveDependencyGraph transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(resolver.getAllRules(args.getDeps()));
    ImmutableSet<SourcePath> manifestFiles = transitiveDependencyGraph.findManifestFiles();

    // The only rules that need to be built before this AndroidManifest are those
    // responsible for generating the AndroidManifest.xml files in the manifestFiles set (and
    // possibly the skeleton).
    //
    // If the skeleton is a BuildTargetSourcePath, then its build rule must also be in the deps.
    // The skeleton does not appear to be in either params.getDeclaredDeps() or
    // params.getExtraDeps(), even though the type of Arg.skeleton is SourcePath.
    // TODO(simons): t4744625 This should happen automagically.
    ImmutableSortedSet<BuildRule> newDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(
                ruleFinder.filterBuildRuleInputs(
                    Sets.union(manifestFiles, Collections.singleton(args.getSkeleton()))))
            .build();

    return new AndroidManifest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(newDeps), params.getExtraDeps()),
        args.getSkeleton(),
        manifestFiles);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidManifestDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getSkeleton();

    /**
     * A collection of dependencies that includes android_library rules. The manifest files of the
     * android_library rules will be filtered out to become dependent source files for the {@link
     * AndroidManifest}.
     */
    @Override
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getDeps();
  }
}
