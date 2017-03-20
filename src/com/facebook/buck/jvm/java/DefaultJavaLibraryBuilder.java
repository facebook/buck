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
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class DefaultJavaLibraryBuilder {
  private final BuildRuleParams params;
  private final SourcePathResolver resolver;
  private final SourcePathRuleFinder ruleFinder;
  private final CompileToJarStepFactory compileStepFactory;
  private JavacOptions javacOptions;
  private ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  private ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
  private Optional<Path> generatedSourceFolder = Optional.empty();
  private Optional<SourcePath> proguardConfig = Optional.empty();
  private ImmutableList<String> postprocessClassesCommands = ImmutableList.of();
  private ImmutableSortedSet<BuildRule> exportedDeps = ImmutableSortedSet.of();
  private ImmutableSortedSet<BuildRule> providedDeps = ImmutableSortedSet.of();
  private ImmutableSortedSet<SourcePath> abiInputs = ImmutableSortedSet.of();
  private boolean trackClassUsage = false;
  private ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries = ImmutableSet.of();
  private Optional<Path> resourcesRoot = Optional.empty();
  private Optional<SourcePath> manifestFile = Optional.empty();
  private Optional<String> mavenCoords = Optional.empty();
  private ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  private ImmutableSet<Pattern> classesToRemoveFromJar = ImmutableSet.of();

  protected DefaultJavaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CompileToJarStepFactory compileStepFactory) {
    this.params = params;
    this.compileStepFactory = compileStepFactory;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    resolver = new SourcePathResolver(ruleFinder);
  }

  public DefaultJavaLibraryBuilder setJavacOptions(JavacOptions javacOptions) {
    this.javacOptions = javacOptions;
    return this;
  }

  public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.Arg args) {
    return setSrcs(args.srcs)
        .setResources(args.resources)
        .setResourcesRoot(args.resourcesRoot)
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
        resolver,
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

  public DefaultJavaLibraryBuilder setExportedDeps(ImmutableSortedSet<BuildRule> exportedDeps) {
    this.exportedDeps = exportedDeps;
    return this;
  }

  public DefaultJavaLibraryBuilder setProvidedDeps(ImmutableSortedSet<BuildRule> providedDeps) {
    this.providedDeps = providedDeps;
    return this;
  }

  public DefaultJavaLibraryBuilder setAbiInputs(ImmutableSortedSet<SourcePath> abiInputs) {
    this.abiInputs = abiInputs;
    return this;
  }

  public DefaultJavaLibraryBuilder setTrackClassUsage(boolean trackClassUsage) {
    this.trackClassUsage = trackClassUsage;
    return this;
  }

  public DefaultJavaLibraryBuilder setAdditionalClasspathEntries(
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries) {
    this.additionalClasspathEntries = additionalClasspathEntries;
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

  public DefaultJavaLibrary build() {
    return newInstance(
        params,
        javacOptions,
        resolver,
        ruleFinder,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        abiInputs,
        trackClassUsage,
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        mavenCoords,
        tests,
        classesToRemoveFromJar);
  }

  protected DefaultJavaLibrary newInstance(
      BuildRuleParams params,
      @SuppressWarnings("unused") JavacOptions javacOptions,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      ImmutableSortedSet<SourcePath> abiInputs,
      boolean trackClassUsage,
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      ImmutableSet<Pattern> classesToRemoveFromJar) {
    return new DefaultJavaLibrary(
        params,
        resolver,
        ruleFinder,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        abiInputs,
        trackClassUsage,
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        mavenCoords,
        tests,
        classesToRemoveFromJar);
  }
}
