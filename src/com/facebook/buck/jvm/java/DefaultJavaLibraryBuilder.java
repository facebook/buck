/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class DefaultJavaLibraryBuilder {
  private final BuildRuleParams params;
  @Nullable
  private final JavaBuckConfig javaBuckConfig;
  protected final BuildRuleResolver buildRuleResolver;
  protected final SourcePathResolver sourcePathResolver;
  protected final SourcePathRuleFinder ruleFinder;
  protected ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  protected ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
  protected Optional<Path> generatedSourceFolder = Optional.empty();
  protected Optional<SourcePath> proguardConfig = Optional.empty();
  protected ImmutableList<String> postprocessClassesCommands = ImmutableList.of();
  protected ImmutableSortedSet<BuildRule> fullJarExportedDeps = ImmutableSortedSet.of();
  protected ImmutableSortedSet<BuildRule> fullJarProvidedDeps = ImmutableSortedSet.of();
  protected boolean trackClassUsage = false;
  protected boolean compileAgainstAbis = false;
  protected Optional<Path> resourcesRoot = Optional.empty();
  protected Optional<SourcePath> manifestFile = Optional.empty();
  protected Optional<String> mavenCoords = Optional.empty();
  protected ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  protected ImmutableSet<Pattern> classesToRemoveFromJar = ImmutableSet.of();
  protected JavacOptionsAmender javacOptionsAmender = JavacOptionsAmender.IDENTITY;
  @Nullable
  protected JavacOptions javacOptions = null;
  @Nullable
  private JavaLibraryDescription.Arg args = null;

  protected DefaultJavaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      JavaBuckConfig javaBuckConfig) {
    this.params = params;
    this.buildRuleResolver = buildRuleResolver;
    this.javaBuckConfig = javaBuckConfig;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = new SourcePathResolver(ruleFinder);
    setCompileAgainstAbis(javaBuckConfig.shouldCompileAgainstAbis());
  }

  protected DefaultJavaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver) {
    this.params = params;
    this.buildRuleResolver = buildRuleResolver;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = new SourcePathResolver(ruleFinder);
    javaBuckConfig = null;
  }

  public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.Arg args) {
    this.args = args;
    return setSrcs(args.srcs)
        .setResources(args.resources)
        .setResourcesRoot(args.resourcesRoot)
        .setProguardConfig(args.proguardConfig)
        .setPostprocessClassesCommands(args.postprocessClassesCommands)
        .setExportedDeps(args.exportedDeps)
        .setProvidedDeps(args.providedDeps)
        .setManifestFile(args.manifestFile)
        .setMavenCoords(args.mavenCoords);
  }

  public DefaultJavaLibraryBuilder setJavacOptions(JavacOptions javacOptions) {
    this.javacOptions = javacOptions;
    return this;
  }

  public DefaultJavaLibraryBuilder setJavacOptionsAmender(JavacOptionsAmender amender) {
    javacOptionsAmender = amender;
    return this;
  }

  public DefaultJavaLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    this.srcs = srcs;
    return this;
  }

  public DefaultJavaLibraryBuilder setResources(
      ImmutableSortedSet<SourcePath> resources) {
    this.resources = ResourceValidator.validateResources(
        sourcePathResolver,
        params.getProjectFilesystem(),
        resources);
    return this;
  }

  public DefaultJavaLibraryBuilder setGeneratedSourceFolder(Optional<Path> generatedSourceFolder) {
    this.generatedSourceFolder = generatedSourceFolder;
    return this;
  }

  public DefaultJavaLibraryBuilder setProguardConfig(Optional<SourcePath> proguardConfig) {
    this.proguardConfig = proguardConfig;
    return this;
  }

  public DefaultJavaLibraryBuilder setPostprocessClassesCommands(
      ImmutableList<String> postprocessClassesCommands) {
    this.postprocessClassesCommands = postprocessClassesCommands;
    return this;
  }

  public DefaultJavaLibraryBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> exportedDeps) {
    this.fullJarExportedDeps = buildRuleResolver.getAllRules(exportedDeps);
    return this;
  }

  @VisibleForTesting
  public DefaultJavaLibraryBuilder setExportedDepRules(ImmutableSortedSet<BuildRule> exportedDeps) {
    this.fullJarExportedDeps = exportedDeps;
    return this;
  }

  public DefaultJavaLibraryBuilder setProvidedDeps(ImmutableSortedSet<BuildTarget> providedDeps) {
    this.fullJarProvidedDeps = buildRuleResolver.getAllRules(providedDeps);
    return this;
  }

  public DefaultJavaLibraryBuilder setTrackClassUsage(boolean trackClassUsage) {
    this.trackClassUsage = trackClassUsage;
    return this;
  }

  public DefaultJavaLibraryBuilder setResourcesRoot(Optional<Path> resourcesRoot) {
    this.resourcesRoot = resourcesRoot;
    return this;
  }

  public DefaultJavaLibraryBuilder setManifestFile(Optional<SourcePath> manifestFile) {
    this.manifestFile = manifestFile;
    return this;
  }

  public DefaultJavaLibraryBuilder setMavenCoords(Optional<String> mavenCoords) {
    this.mavenCoords = mavenCoords;
    return this;
  }

  public DefaultJavaLibraryBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    this.tests = tests;
    return this;
  }

  public DefaultJavaLibraryBuilder setClassesToRemoveFromJar(
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    return this;
  }

  protected DefaultJavaLibraryBuilder setCompileAgainstAbis(boolean compileAgainstAbis) {
    this.compileAgainstAbis = compileAgainstAbis;
    return this;
  }

  public final DefaultJavaLibrary build() throws NoSuchBuildTargetException {
    BuilderHelper helper = newHelper();
    return helper.build();
  }

  public final BuildRule buildAbi() throws NoSuchBuildTargetException {
    return newHelper().buildAbi();
  }

  protected BuilderHelper newHelper() {
    return new BuilderHelper();
  }

  protected class BuilderHelper {
    @Nullable
    private BuildRuleParams finalParams;
    @Nullable
    private ImmutableSortedSet<BuildRule> finalFullJarDeclaredDeps;
    @Nullable
    private ImmutableSortedSet<BuildRule> compileTimeClasspathUnfilteredFullDeps;
    @Nullable
    private ImmutableSortedSet<BuildRule> compileTimeClasspathFullDeps;
    @Nullable
    private ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps;
    @Nullable
    private ImmutableSortedSet<SourcePath> abiInputs;
    @Nullable
    private CompileToJarStepFactory compileStepFactory;
    @Nullable
    private BuildTarget abiJar;

    protected DefaultJavaLibrary build() throws NoSuchBuildTargetException {
      return new DefaultJavaLibrary(
          getFinalParams(),
          sourcePathResolver,
          ruleFinder,
          srcs,
          resources,
          generatedSourceFolder,
          proguardConfig,
          postprocessClassesCommands,
          getFinalFullJarDeclaredDeps(),
          fullJarExportedDeps,
          fullJarProvidedDeps,
          getFinalCompileTimeClasspathSourcePaths(),
          getAbiInputs(),
          getAbiJar(),
          trackClassUsage,
          getCompileStepFactory(),
          resourcesRoot,
          manifestFile,
          mavenCoords,
          tests,
          classesToRemoveFromJar);
    }

    protected BuildTarget getAbiJar() {
      if (abiJar == null) {
        abiJar = shouldBuildAbiFromSource()
            ? HasJavaAbi.getSourceAbiJar(params.getBuildTarget())
            : HasJavaAbi.getClassAbiJar(params.getBuildTarget());
      }

      return abiJar;
    }

    private boolean shouldBuildAbiFromSource() {
      return false;
    }

    protected BuildRule buildAbi() throws NoSuchBuildTargetException {
      BuildTarget abiTarget = params.getBuildTarget();
      BuildTarget libraryTarget = HasJavaAbi.getLibraryTarget(abiTarget);
      BuildRule libraryRule = buildRuleResolver.requireRule(libraryTarget);

      return CalculateAbiFromClasses.of(
          abiTarget,
          ruleFinder,
          params,
          Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()));
    }

    protected final BuildRuleParams getFinalParams() throws NoSuchBuildTargetException {
      if (finalParams == null) {
        finalParams = buildFinalParams();
      }

      return finalParams;
    }

    protected final ImmutableSortedSet<BuildRule> getFinalFullJarDeclaredDeps() {
      if (finalFullJarDeclaredDeps == null) {
        finalFullJarDeclaredDeps = ImmutableSortedSet.copyOf(Iterables.concat(
            params.getDeclaredDeps().get(),
            getCompileStepFactory().getDeclaredDeps(ruleFinder)));
      }

      return finalFullJarDeclaredDeps;
    }

    protected final ImmutableSortedSet<SourcePath> getFinalCompileTimeClasspathSourcePaths()
        throws NoSuchBuildTargetException {
      ImmutableSortedSet<BuildRule> buildRules = compileAgainstAbis ?
          getCompileTimeClasspathAbiDeps() :
          getCompileTimeClasspathFullDeps();

      return buildRules.stream()
          .map(BuildRule::getSourcePathToOutput)
          .filter(Objects::nonNull)
          .collect(MoreCollectors.toImmutableSortedSet());
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
      if (compileTimeClasspathFullDeps == null) {
        compileTimeClasspathFullDeps = getCompileTimeClasspathUnfilteredFullDeps().stream()
            .filter(dep -> dep instanceof HasJavaAbi)
            .collect(MoreCollectors.toImmutableSortedSet());
      }

      return compileTimeClasspathFullDeps;
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps()
        throws NoSuchBuildTargetException {
      if (compileTimeClasspathAbiDeps == null) {
        compileTimeClasspathAbiDeps = buildCompileTimeClasspathAbiDeps();
      }

      return compileTimeClasspathAbiDeps;
    }

    protected final ImmutableSortedSet<SourcePath> getAbiInputs()
        throws NoSuchBuildTargetException {
      if (abiInputs == null) {
        abiInputs = buildAbiInputs();
      }

      return abiInputs;
    }

    protected final CompileToJarStepFactory getCompileStepFactory() {
      if (compileStepFactory == null) {
        compileStepFactory = buildCompileStepFactory();
      }

      return compileStepFactory;
    }

    protected BuildRuleParams buildFinalParams() throws NoSuchBuildTargetException {
      ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps = getCompileTimeClasspathAbiDeps();

      return params.copyReplacingDeclaredAndExtraDeps(
          this::getFinalFullJarDeclaredDeps,
          () -> ImmutableSortedSet.copyOf(Iterables.concat(
              params.getExtraDeps().get(),
              Sets.difference(getCompileTimeClasspathUnfilteredFullDeps(), params.getBuildDeps()),
              Sets.difference(compileTimeClasspathAbiDeps, params.getBuildDeps()),
              getCompileStepFactory().getExtraDeps(ruleFinder))));
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
      if (compileTimeClasspathUnfilteredFullDeps == null) {
        Iterable<BuildRule> firstOrderDeps = Iterables.concat(
            getFinalFullJarDeclaredDeps(),
            fullJarExportedDeps,
            fullJarProvidedDeps);

        ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
            BuildRules.getExportedRules(firstOrderDeps);

        compileTimeClasspathUnfilteredFullDeps = RichStream.from(Iterables.concat(
            firstOrderDeps,
            rulesExportedByDependencies))
            .collect(MoreCollectors.toImmutableSortedSet());
      }

      return compileTimeClasspathUnfilteredFullDeps;
    }

    protected ImmutableSortedSet<BuildRule> buildCompileTimeClasspathAbiDeps()
        throws NoSuchBuildTargetException {
      return JavaLibraryRules.getAbiRules(buildRuleResolver, getCompileTimeClasspathFullDeps());
    }

    protected ImmutableSortedSet<SourcePath> buildAbiInputs() throws NoSuchBuildTargetException {
        return getCompileTimeClasspathAbiDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet());
    }

    protected CompileToJarStepFactory buildCompileStepFactory() {
      return new JavacToJarStepFactory(
          JavacFactory.create(
              ruleFinder,
              Preconditions.checkNotNull(javaBuckConfig),
              args),
          Preconditions.checkNotNull(javacOptions),
          javacOptionsAmender);
    }
  }
}
