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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.versions.VersionRoot;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

public class JavaBinaryDescription implements
    Description<JavaBinaryDescription.Args>,
    ImplicitDepsInferringDescription<JavaBinaryDescription.Args>,
    VersionRoot<JavaBinaryDescription.Args> {

  private static final Flavor FAT_JAR_INNER_JAR_FLAVOR = ImmutableFlavor.of("inner-jar");

  private final JavacOptions javacOptions;
  private final CxxPlatform cxxPlatform;
  private final JavaOptions javaOptions;

  public JavaBinaryDescription(
      JavaOptions javaOptions,
      JavacOptions javacOptions,
      CxxPlatform cxxPlatform) {
    this.javaOptions = javaOptions;
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.cxxPlatform = Preconditions.checkNotNull(cxxPlatform);
  }

  @Override
  public Args createUnpopulatedConstructorArg() {
    return new Args();
  }

  @Override
  public <A extends Args> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ImmutableMap<String, SourcePath> nativeLibraries =
        JavaLibraryRules.getNativeLibraries(params.getDeps(), cxxPlatform);
    BuildRuleParams binaryParams = params;

    // If we're packaging native libraries, we'll build the binary JAR in a separate rule and
    // package it into the final fat JAR, so adjust it's params to use a flavored target.
    if (!nativeLibraries.isEmpty()) {
      binaryParams = params.copyWithChanges(
          params.getBuildTarget().withAppendedFlavors(FAT_JAR_INNER_JAR_FLAVOR),
          params.getDeclaredDeps(),
          params.getExtraDeps());
    }

    // Construct the build rule to build the binary JAR.
    ImmutableSet<JavaLibrary> transitiveClasspathDeps =
        JavaLibraryClasspathProvider.getClasspathDeps(binaryParams.getDeps());
    ImmutableSet<Path> transitiveClasspaths =
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(transitiveClasspathDeps);
    BuildRule rule = new JavaBinary(
        binaryParams.appendExtraDeps(transitiveClasspathDeps),
        pathResolver,
        javaOptions.getJavaRuntimeLauncher(),
        args.mainClass.orElse(null),
        args.manifestFile.orElse(null),
        args.mergeManifests.orElse(true),
        args.metaInfDirectory.orElse(null),
        args.blacklist,
        transitiveClasspathDeps,
        transitiveClasspaths);

    // If we're packaging native libraries, construct the rule to build the fat JAR, which packages
    // up the original binary JAR and any required native libraries.
    if (!nativeLibraries.isEmpty()) {
      BuildRule innerJarRule = rule;
      resolver.addToIndex(innerJarRule);
      SourcePath innerJar = new BuildTargetSourcePath(innerJarRule.getBuildTarget());
      rule = new JarFattener(
          params.appendExtraDeps(
              Suppliers.<Iterable<BuildRule>>ofInstance(
                  ruleFinder.filterBuildRuleInputs(
                      ImmutableList.<SourcePath>builder()
                          .add(innerJar)
                          .addAll(nativeLibraries.values())
                          .build()))),
          pathResolver,
          ruleFinder,
          javacOptions,
          innerJar,
          nativeLibraries,
          javaOptions.getJavaRuntimeLauncher());
    }

    return rule;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Args constructorArg) {
    return CxxPlatforms.getParseTimeDeps(cxxPlatform);
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Args extends AbstractDescriptionArg implements HasTests {
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<String> mainClass;
    public Optional<SourcePath> manifestFile;
    public Optional<Boolean> mergeManifests;
    public Optional<Path> metaInfDirectory;
    public ImmutableSet<Pattern> blacklist = ImmutableSet.of();
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }

  }
}
