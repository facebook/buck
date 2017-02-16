/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryRules;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.MavenUberJar;
import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.Optional;


public class KotlinLibraryDescription implements
    Description<KotlinLibraryDescription.Arg>, Flavored {

  private final KotlinBuckConfig kotlinBuckConfig;

  public static final ImmutableSet<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      JavaLibrary.SRC_JAR,
      JavaLibrary.MAVEN_JAR);

  @VisibleForTesting
  final JavacOptions defaultOptions;

  public KotlinLibraryDescription(
      KotlinBuckConfig kotlinBuckConfig,
      JavacOptions templateOptions) {
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.defaultOptions = templateOptions;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
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
      A args) throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.
    if (CalculateAbi.isAbiTarget(target)) {
      BuildTarget libraryTarget = CalculateAbi.getLibraryTarget(params.getBuildTarget());
      resolver.requireRule(libraryTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          new BuildTargetSourcePath(libraryTarget));
    }

    ImmutableSortedSet<Flavor> flavors = target.getFlavors();

    BuildRuleParams paramsWithMavenFlavor = null;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {
      paramsWithMavenFlavor = params;

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      params = params.copyWithBuildTarget(
          params.getBuildTarget().withoutFlavors(ImmutableSet.of(JavaLibrary.MAVEN_JAR)));
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      args.mavenCoords = args.mavenCoords.map(input -> AetherUtil.addClassifier(
          input,
          AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            params,
            args.srcs,
            args.mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            Preconditions.checkNotNull(paramsWithMavenFlavor),
            args.srcs,
            args.mavenCoords,
            args.mavenPomTemplate);
      }
    }

    JavacOptions javacOptions = JavacOptionsFactory.create(
        defaultOptions,
        params,
        resolver,
        ruleFinder,
        args);

    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps);
    BuildRuleParams javaLibraryParams =
        params.appendExtraDeps(
            Iterables.concat(
                BuildRules.getExportedRules(
                    Iterables.concat(
                        params.getDeclaredDeps().get(),
                        exportedDeps,
                        resolver.getAllRules(args.providedDeps))),
                ruleFinder.filterBuildRuleInputs(
                    javacOptions.getInputs(ruleFinder))));
    DefaultKotlinLibrary defaultKotlinLibrary =
        new DefaultKotlinLibrary(
            javaLibraryParams,
            pathResolver,
            ruleFinder,
            args.srcs,
            validateResources(
                pathResolver,
                params.getProjectFilesystem(),
                args.resources),
            Optional.empty(),
            Optional.empty(),
            ImmutableList.of(),
            exportedDeps,
            resolver.getAllRules(args.providedDeps),
            JavaLibraryRules.getAbiInputs(resolver, javaLibraryParams.getDeps()),
            false,
            ImmutableSet.of(),
            new KotlincToJarStepFactory(
                kotlinBuckConfig.getKotlinCompiler().get(),
                args.extraKotlincArguments),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ImmutableSortedSet.of(),
            ImmutableSet.of());

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultKotlinLibrary;
    } else {
      return MavenUberJar.create(
          defaultKotlinLibrary,
          Preconditions.checkNotNull(paramsWithMavenFlavor),
          args.mavenCoords,
          args.mavenPomTemplate);
    }
  }


  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public ImmutableList<String> extraKotlincArguments = ImmutableList.of();
  }
}
