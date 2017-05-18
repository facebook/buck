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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class ScalaLibraryDescription
    implements Description<ScalaLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            ScalaLibraryDescription.AbstractScalaLibraryDescriptionArg> {

  private final ScalaBuckConfig scalaBuckConfig;

  public ScalaLibraryDescription(ScalaBuckConfig scalaBuckConfig) {
    this.scalaBuckConfig = scalaBuckConfig;
  }

  @Override
  public Class<ScalaLibraryDescriptionArg> getConstructorArgType() {
    return ScalaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams rawParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ScalaLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    ScalaLibraryBuilder scalaLibraryBuilder =
        new ScalaLibraryBuilder(targetGraph, rawParams, resolver, cellRoots, scalaBuckConfig)
            .setArgs(args);

    return HasJavaAbi.isAbiTarget(rawParams.getBuildTarget())
        ? scalaLibraryBuilder.buildAbi()
        : scalaLibraryBuilder.build();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractScalaLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins())
        .addAll(OptionalCompat.asSet(scalaBuckConfig.getScalacTarget()));
  }

  // Note: scala does not have a exported_deps because scala needs the transitive closure of
  // dependencies to compile. deps is effectively exported_deps.
  interface CoreArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasTests {
    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getResources();

    ImmutableList<String> getExtraArguments();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getProvidedDeps();

    @Hint(isInput = false)
    Optional<Path> getResourcesRoot();

    Optional<SourcePath> getManifestFile();

    Optional<String> getMavenCoords();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractScalaLibraryDescriptionArg extends CoreArg {}
}
