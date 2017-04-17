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


import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

public class JavaLibraryDescription implements
    Description<JavaLibraryDescription.Arg>,
    Flavored,
    VersionPropagator<JavaLibraryDescription.Arg> {

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      Javadoc.DOC_JAR,
      JavaLibrary.SRC_JAR,
      JavaLibrary.MAVEN_JAR);

  private final JavaBuckConfig javaBuckConfig;
  @VisibleForTesting
  private final JavacOptions defaultOptions;

  public JavaLibraryDescription(
      JavaBuckConfig javaBuckConfig,
      JavacOptions defaultOptions) {
    this.javaBuckConfig = javaBuckConfig;
    this.defaultOptions = defaultOptions;
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
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    ImmutableSortedSet<Flavor> flavors = target.getFlavors();
    JarShape shape = params.getBuildTarget().getFlavors().contains(JavaLibrary.MAVEN_JAR) ?
        JarShape.MAVEN : JarShape.SINGLE;

    if (flavors.contains(Javadoc.DOC_JAR)) {
      BuildTarget unflavored = BuildTarget.of(target.getUnflavoredBuildTarget());
      BuildRule baseLibrary = resolver.requireRule(unflavored);

      JarShape.Summary summary = shape.gatherDeps(baseLibrary);
      ImmutableSet<SourcePath> sources = summary.getPackagedRules().stream()
          .filter(HasSources.class::isInstance)
          .map(rule -> ((HasSources) rule).getSources())
          .flatMap(Collection::stream)
          .collect(MoreCollectors.toImmutableSet());

      // In theory, the only deps we need are the ones that contribute to the sourcepaths. However,
      // javadoc wants to have classes being documented have all their deps be available somewhere.
      // Ideally, we'd not build everything, but then we're not able to document any classes that
      // rely on auto-generated classes, such as those created by the Immutables library. Oh well.
      // Might as well add them as deps. *sigh*
      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      // Sourcepath deps
      deps.addAll(ruleFinder.filterBuildRuleInputs(sources));
      // Classpath deps
      deps.add(baseLibrary);
      deps.addAll(
          summary.getClasspath().stream()
              .filter(rule -> HasClasspathEntries.class.isAssignableFrom(rule.getClass()))
              .flatMap(rule -> rule.getTransitiveClasspathDeps().stream())
              .iterator());
      BuildRuleParams emptyParams = params.copyReplacingDeclaredAndExtraDeps(
          Suppliers.ofInstance(deps.build()),
          Suppliers.ofInstance(ImmutableSortedSet.of()));

      return new Javadoc(
          emptyParams,
          args.mavenCoords,
          args.mavenPomTemplate,
          summary.getMavenDeps(),
          sources);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      BuildTarget unflavored = BuildTarget.of(target.getUnflavoredBuildTarget());
      BuildRule baseLibrary = resolver.requireRule(unflavored);

      JarShape.Summary summary = shape.gatherDeps(baseLibrary);
      ImmutableSet<SourcePath> sources = summary.getPackagedRules().stream()
          .filter(HasSources.class::isInstance)
          .map(rule -> ((HasSources) rule).getSources())
          .flatMap(Collection::stream)
          .collect(MoreCollectors.toImmutableSet());

      ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
      // Sourcepath deps
      deps.addAll(ruleFinder.filterBuildRuleInputs(sources));
      BuildRuleParams emptyParams = params.copyReplacingDeclaredAndExtraDeps(
          Suppliers.ofInstance(deps.build()),
          Suppliers.ofInstance(ImmutableSortedSet.of()));

      return new SourceJar(
          emptyParams,
          args.javaVersion.orElse(javaBuckConfig.getDefaultJavacOptions().getSourceLevel()),
          sources,
          args.mavenCoords,
          args.mavenPomTemplate,
          summary.getMavenDeps());
    }

    BuildRuleParams paramsWithMavenFlavor = null;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {
      paramsWithMavenFlavor = params;

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      params = params.withoutFlavor(JavaLibrary.MAVEN_JAR);
    }

    JavacOptions javacOptions = JavacOptionsFactory.create(
        defaultOptions,
        params,
        resolver,
        args);

    DefaultJavaLibraryBuilder defaultJavaLibraryBuilder =
        DefaultJavaLibrary
            .builder(params, resolver, javaBuckConfig)
            .setArgs(args)
            .setJavacOptions(javacOptions)
            .setGeneratedSourceFolder(javacOptions.getGeneratedSourceFolderName())
            .setTrackClassUsage(javacOptions.trackClassUsage());

    if (HasJavaAbi.isAbiTarget(target)) {
      return defaultJavaLibraryBuilder.buildAbi();
    }

    DefaultJavaLibrary defaultJavaLibrary = defaultJavaLibraryBuilder.build();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      resolver.addToIndex(defaultJavaLibrary);
      return MavenUberJar.create(
          defaultJavaLibrary,
          Preconditions.checkNotNull(paramsWithMavenFlavor),
          args.mavenCoords,
          args.mavenPomTemplate);
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JvmLibraryArg implements HasTests {
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
    public ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();

    public Optional<SourcePath> proguardConfig;
    public ImmutableList<String> postprocessClassesCommands = ImmutableList.of();

    @Hint(isInput = false)
    public Optional<Path> resourcesRoot;
    public Optional<SourcePath> manifestFile;
    public Optional<String> mavenCoords;
    public Optional<SourcePath> mavenPomTemplate;

    public Optional<Boolean> autodeps;
    public ImmutableSortedSet<BuildTarget> providedDeps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> exportedDeps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();

    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }
  }
}
