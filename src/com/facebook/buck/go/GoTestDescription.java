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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;

public class GoTestDescription implements Description<GoTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_test");

  private final GoBuckConfig goBuckConfig;

  private final CxxPlatform cxxPlatform;

  public GoTestDescription(GoBuckConfig goBuckConfig, CxxPlatform cxxPlatform) {
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

  private GoTestMain requireTestMainGenRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableSet<SourcePath> srcs,
      Path packageName) {

    Tool testMainGenerator = GoDescriptors.getTestMainGenerator(
        goBuckConfig,
        params,
        resolver,
        cxxPlatform,
        params.getProjectFilesystem());

    SourcePathResolver sourceResolver = new SourcePathResolver(resolver);
    GoTestMain generatedTestMain = new GoTestMain(
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(ImmutableFlavor.of("test-main-src"))
                .build(),
            Suppliers.ofInstance(ImmutableSortedSet.copyOf(
                testMainGenerator.getDeps(sourceResolver))),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
        ),
        sourceResolver,
        testMainGenerator,
        srcs,
        packageName
    );
    resolver.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildTarget libraryTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("test-library"))
            .build();

    Path defaultPackageName = goBuckConfig.getDefaultPackageName(params.getBuildTarget());
    defaultPackageName = defaultPackageName.resolveSibling(
        defaultPackageName.getFileName() + "_test");

    GoLibrary lib = GoDescriptors.createGoLibraryRule(
        params.copyWithBuildTarget(libraryTarget),
        resolver,
        goBuckConfig,
        args.packageName.transform(MorePaths.TO_PATH).or(defaultPackageName),
        args.srcs,
        args.compilerFlags.or(ImmutableList.<String>of()),
        ImmutableSortedSet.<BuildTarget>of()

    );
    resolver.addToIndex(lib);

    GoTestMain generatedTestMain = requireTestMainGenRule(
        params, resolver, args.srcs, lib.getGoPackageName());
    GoBinary testMain = GoDescriptors.createGoBinaryRule(
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(ImmutableFlavor.of("test-main"))
                .build(),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(lib)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(generatedTestMain))
        ),
        resolver,
        goBuckConfig,
        cxxPlatform,
        ImmutableSet.<SourcePath>of(new BuildTargetSourcePath(generatedTestMain.getBuildTarget())),
        args.compilerFlags.or(ImmutableList.<String>of()),
        args.linkerFlags.or(ImmutableList.<String>of()));
    resolver.addToIndex(testMain);

    return new GoTest(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(testMain)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
        ),
        new SourcePathResolver(resolver),
        testMain,
        args.labels.get(),
        args.contacts.get(),
        args.runTestSeparately.or(false));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSet<SourcePath> srcs;
    public Optional<String> packageName;
    public Optional<List<String>> compilerFlags;
    public Optional<List<String>> linkerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<Boolean> runTestSeparately;
  }
}
