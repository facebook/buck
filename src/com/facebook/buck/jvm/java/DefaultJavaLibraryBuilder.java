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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public class DefaultJavaLibraryBuilder {
  protected final BuildRuleParams params;
  protected final BuildRuleResolver buildRuleResolver;
  protected final SourcePathResolver sourcePathResolver;
  protected final SourcePathRuleFinder ruleFinder;
  protected final CompileToJarStepFactory compileStepFactory;
  protected ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  protected ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
  protected Optional<Path> generatedSourceFolder = Optional.empty();
  protected Optional<SourcePath> proguardConfig = Optional.empty();
  protected ImmutableList<String> postprocessClassesCommands = ImmutableList.of();
  protected ImmutableSortedSet<BuildRule> exportedDeps = ImmutableSortedSet.of();
  protected ImmutableSortedSet<BuildRule> providedDeps = ImmutableSortedSet.of();
  protected boolean trackClassUsage = false;
  protected Optional<Path> resourcesRoot = Optional.empty();
  protected Optional<SourcePath> manifestFile = Optional.empty();
  protected Optional<String> mavenCoords = Optional.empty();
  protected ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  protected ImmutableSet<Pattern> classesToRemoveFromJar = ImmutableSet.of();
  protected boolean suggestDependencies = false;

  protected DefaultJavaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CompileToJarStepFactory compileStepFactory) {
    this.params = params;
    this.buildRuleResolver = buildRuleResolver;
    this.compileStepFactory = compileStepFactory;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = new SourcePathResolver(ruleFinder);
  }

  public DefaultJavaLibraryBuilder setConfigAndArgs(
      JavaBuckConfig config,
      JavaLibraryDescription.Arg args) {
    setSuggestDependencies(config.shouldSuggestDependencies());

    return setArgs(args);
  }

  protected DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.Arg args) {
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
    this.exportedDeps = buildRuleResolver.getAllRules(exportedDeps);
    return this;
  }

  @VisibleForTesting
  public DefaultJavaLibraryBuilder setExportedDepRules(ImmutableSortedSet<BuildRule> exportedDeps) {
    this.exportedDeps = exportedDeps;
    return this;
  }

  public DefaultJavaLibraryBuilder setProvidedDeps(ImmutableSortedSet<BuildTarget> providedDeps) {
    this.providedDeps = buildRuleResolver.getAllRules(providedDeps);
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

  public DefaultJavaLibraryBuilder setSuggestDependencies(boolean suggestDependencies) {
    this.suggestDependencies = suggestDependencies;
    return this;
  }

  protected ImmutableSortedSet<SourcePath> getAbiInputs() throws NoSuchBuildTargetException {
    return JavaLibraryRules.getAbiInputs(buildRuleResolver, params.getBuildDeps());
  }

  public DefaultJavaLibrary build() throws NoSuchBuildTargetException {
    return new DefaultJavaLibrary(
        params,
        sourcePathResolver,
        ruleFinder,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        getAbiInputs(),
        trackClassUsage,
        compileStepFactory,
        suggestDependencies,
        resourcesRoot,
        manifestFile,
        mavenCoords,
        tests,
        classesToRemoveFromJar);
  }
}
