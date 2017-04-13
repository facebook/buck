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


import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;

public class ScalaLibraryDescription implements Description<ScalaLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<ScalaLibraryDescription.Arg> {

  private final ScalaBuckConfig scalaBuckConfig;

  public ScalaLibraryDescription(ScalaBuckConfig scalaBuckConfig) {
    this.scalaBuckConfig = scalaBuckConfig;
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
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    ScalaLibraryBuilder scalaLibraryBuilder = new ScalaLibraryBuilder(
        rawParams,
        resolver,
        scalaBuckConfig)
        .setArgs(args);

    return HasJavaAbi.isAbiTarget(rawParams.getBuildTarget())
        ? scalaLibraryBuilder.buildAbi()
        : scalaLibraryBuilder.build();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins())
        .addAll(OptionalCompat.asSet(scalaBuckConfig.getScalacTarget()));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
    public ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
    public ImmutableList<String> extraArguments = ImmutableList.of();
    // Note: scala does not have a exported_deps because scala needs the transitive closure of
    // dependencies to compile. deps is effectively exported_deps.
    public ImmutableSortedSet<BuildTarget> providedDeps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();

    @Hint(isInput = false)
    public Optional<Path> resourcesRoot;
    public Optional<SourcePath> manifestFile;
    public Optional<String> mavenCoords;

    @Hint(isDep = false)
    public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  }

}
