/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Paths;
import java.util.List;

public class GoBinaryDescription implements Description<GoBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_binary");

  private final GoBuckConfig goBuckConfig;

  private final CxxPlatform cxxPlatform;

  public GoBinaryDescription(GoBuckConfig goBuckConfig, CxxPlatform cxxPlatform) {
    this.goBuckConfig = goBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildTarget libraryTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("compile"))
            .build();
    GoLibrary library = GoDescriptors.createGoLibraryRule(
        params.copyWithBuildTarget(libraryTarget),
        resolver,
        goBuckConfig,
        Paths.get("main"),
        args.srcs,
        args.compilerFlags.or(ImmutableList.<String>of())
    );
    resolver.addToIndex(library);

    BuildRuleParams binaryParams = params.copyWithDeps(
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(library)),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
    );

    GoSymlinkTree symlinkTree = GoDescriptors.requireTransitiveSymlinkTreeRule(
        binaryParams,
        resolver);

    return new GoBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(symlinkTree, library)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        new SourcePathResolver(resolver),
        cxxPlatform.getLd(),
        symlinkTree,
        library,
        goBuckConfig.getGoLinker().get(),
        ImmutableList.<String>builder()
            .addAll(goBuckConfig.getLinkerFlags())
            .addAll(args.linkerFlags.or(ImmutableList.<String>of()))
            .build());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSet<SourcePath> srcs;
    public Optional<List<String>> compilerFlags;
    public Optional<List<String>> linkerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
