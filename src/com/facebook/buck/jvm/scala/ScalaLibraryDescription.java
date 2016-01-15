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

package com.facebook.buck.jvm.scala;

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class ScalaLibraryDescription implements Description<ScalaLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<ScalaLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("scala_library");

  private final ScalaBuckConfig scalaBuckConfig;

  public ScalaLibraryDescription(
      ScalaBuckConfig scalaBuckConfig) {
    this.scalaBuckConfig = scalaBuckConfig;
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
      final BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Tool scalac = scalaBuckConfig.getScalac(resolver);

    final BuildRule scalaLibrary = resolver.getRule(scalaBuckConfig.getScalaLibraryTarget());
    BuildRuleParams params = rawParams.copyWithDeps(
        new Supplier<ImmutableSortedSet<BuildRule>>() {
          @Override
          public ImmutableSortedSet<BuildRule> get() {
            return ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(rawParams.getDeclaredDeps().get())
                .add(scalaLibrary)
                .build();
          }
        },
        rawParams.getExtraDeps()
    );

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    DefaultJavaLibrary defaultJavaLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps.get()))),
                        scalac.getDeps(pathResolver))),
                pathResolver,
                args.srcs.get(),
                validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                /* generatedSourceFolder */ Optional.<Path>absent(),
                /* proguardConfig */ Optional.<SourcePath>absent(),
                /* postprocessClassesCommands */ ImmutableList.<String>of(),
                params.getDeclaredDeps().get(),
                resolver.getAllRules(args.providedDeps.get()),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                new ScalacToJarStepFactory(
                    scalaBuckConfig.getScalac(resolver),
                    ImmutableList.<String>builder()
                        .addAll(scalaBuckConfig.getCompilerFlags())
                        .addAll(args.extraArguments.get())
                        .build()),
                args.resourcesRoot,
                args.mavenCoords,
                args.tests.get()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(defaultJavaLibrary.getBuildTarget())));

    return defaultJavaLibrary;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    return ImmutableList.<BuildTarget>builder()
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getScalacTarget().asSet())
        .build();
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableList<String>> extraArguments;
    // Note: scala does not have a exported_deps because scala needs the transitive closure of
    // dependencies to compile. deps is effectively exported_deps.
    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;

    @Hint(isInput = false)
    public Optional<Path> resourcesRoot;
    public Optional<String> mavenCoords;

    @Hint(isDep = false)
    public Optional<ImmutableSortedSet<BuildTarget>> tests;
  }

}
