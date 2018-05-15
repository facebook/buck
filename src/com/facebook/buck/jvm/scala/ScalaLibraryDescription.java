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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.MavenUberJar;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class ScalaLibraryDescription
    implements Description<ScalaLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            ScalaLibraryDescription.AbstractScalaLibraryDescriptionArg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(JavaLibrary.MAVEN_JAR, JavaLibrary.SRC_JAR);

  private final ToolchainProvider toolchainProvider;
  private final ScalaBuckConfig scalaBuckConfig;
  private final JavaBuckConfig javaBuckConfig;

  public ScalaLibraryDescription(
      ToolchainProvider toolchainProvider,
      ScalaBuckConfig scalaBuckConfig,
      JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.scalaBuckConfig = scalaBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public Class<ScalaLibraryDescriptionArg> getConstructorArgType() {
    return ScalaLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams rawParams,
      ScalaLibraryDescriptionArg args) {
    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    BuildTarget buildTargetWithMavenFlavor = buildTarget;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      buildTarget = buildTarget.withoutFlavors(JavaLibrary.MAVEN_JAR);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      Optional<String> mavenCoords =
          args.getMavenCoords()
              .map(input -> AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            buildTarget, projectFilesystem, rawParams, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            buildTargetWithMavenFlavor,
            projectFilesystem,
            rawParams,
            args.getSrcs(),
            mavenCoords,
            args.getMavenPomTemplate());
      }
    }

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            context.getProjectFilesystem(),
            context.getBuildRuleResolver(),
            args);

    DefaultJavaLibraryRules scalaLibraryBuilder =
        ScalaLibraryBuilder.newInstance(
                buildTarget,
                context.getProjectFilesystem(),
                context.getToolchainProvider(),
                rawParams,
                context.getBuildRuleResolver(),
                context.getCellPathResolver(),
                scalaBuckConfig,
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .build();

    if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return scalaLibraryBuilder.buildAbi();
    }

    JavaLibrary defaultScalaLibrary = scalaLibraryBuilder.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultScalaLibrary;
    } else {
      context.getBuildRuleResolver().addToIndex(defaultScalaLibrary);
      return MavenUberJar.create(
          defaultScalaLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          rawParams,
          args.getMavenCoords(),
          args.getMavenPomTemplate());
    }
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
        .addAll(scalaBuckConfig.getCompilerPlugins());
    Optionals.addIfPresent(scalaBuckConfig.getScalacTarget(), extraDepsBuilder);
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {

    @Override
    ImmutableList<String> getExtraArguments();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractScalaLibraryDescriptionArg extends CoreArg {}
}
