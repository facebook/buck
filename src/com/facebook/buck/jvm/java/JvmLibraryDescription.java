/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class JvmLibraryDescription<T extends JvmLibraryDescription.Arg>
    implements Description<T>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_library");
  public static final ImmutableSet<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      JavaLibrary.SRC_JAR,
      JavaLibrary.MAVEN_JAR);

  private final JvmLibraryConfigurable<T> configurable;

  public JvmLibraryDescription(JvmLibraryConfigurable<T> configurable) {
    this.configurable = configurable;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return configurable.getBuildRuleType();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public T createUnpopulatedConstructorArg() {
    return configurable.createUnpopulatedConstructorArg();
  }

  @Override
  public <U extends T> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      U args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

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
      args.mavenCoords = args.mavenCoords.transform(
          new Function<String, String>() {
            @Override
            public String apply(String input) {
              return AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES);
            }
          });

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            params,
            pathResolver,
            args.srcs.get(),
            args.mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            Preconditions.checkNotNull(paramsWithMavenFlavor),
            pathResolver,
            args.srcs.get(),
            args.mavenCoords);
      }
    }

    JvmLibraryConfiguration configuration = configurable.createConfiguration(
        params,
        resolver,
        pathResolver,
        args);

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps.get());

    DefaultJavaLibrary defaultJavaLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                exportedDeps,
                                resolver.getAllRules(args.providedDeps.get()))),
                        configuration.additionalBuildRules)),
                pathResolver,
                args.srcs.get(),
                validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                configuration.generatedSourceDirectory,
                args.proguardConfig.transform(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())),
                args.postprocessClassesCommands.get(),
                exportedDeps,
                resolver.getAllRules(args.providedDeps.get()),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                configuration.compileToJarStepFactory,
                args.resourcesRoot,
                args.mavenCoords,
                args.tests.get()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(defaultJavaLibrary.getBuildTarget())));


    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      return MavenUberJar.create(
          defaultJavaLibrary,
          Preconditions.checkNotNull(paramsWithMavenFlavor),
          pathResolver,
          args.mavenCoords);
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JvmLibraryArg implements HasTests {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;

    public Optional<Path> proguardConfig;
    public Optional<ImmutableList<String>> postprocessClassesCommands;

    @Hint(isInput = false)
    public Optional<Path> resourcesRoot;
    public Optional<String> mavenCoords;

    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;

    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }
  }
}
