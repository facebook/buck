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

package com.facebook.buck.java;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.DefaultDirectoryTraverser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaBinaryDescription implements Description<JavaBinaryDescription.Args> {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_binary");

  private static final Flavor FAT_JAR_INNER_JAR_FLAVOR = ImmutableFlavor.of("inner-jar");

  private final JavacOptions javacOptions;
  private final CxxPlatform cxxPlatform;

  public JavaBinaryDescription(
      JavacOptions javacOptions,
      CxxPlatform cxxPlatform) {
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.cxxPlatform = Preconditions.checkNotNull(cxxPlatform);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
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
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> nativeLibraries =
        JavaLibraryRules.getNativeLibraries(targetGraph, params.getDeps(), cxxPlatform);
    BuildRuleParams binaryParams = params;

    // If we're packaging native libraries, we'll build the binary JAR in a separate rule and
    // package it into the final fat JAR, so adjust it's params to use a flavored target.
    if (!nativeLibraries.isEmpty()) {
      binaryParams = params.copyWithChanges(
          BuildTarget.builder(params.getBuildTarget()).addFlavors(FAT_JAR_INNER_JAR_FLAVOR).build(),
          params.getDeclaredDeps(),
          params.getExtraDeps());
    }

    // Construct the build rule to build the binary JAR.
    ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries =
        Classpaths.getClasspathEntries(binaryParams.getDeps());
    BuildRule rule = new JavaBinary(
        binaryParams.appendExtraDeps(transitiveClasspathEntries.keys()),
        pathResolver,
        args.mainClass.orNull(),
        args.manifestFile.orNull(),
        args.mergeManifests.or(true),
        args.metaInfDirectory.orNull(),
        args.blacklist.or(ImmutableSortedSet.<String>of()),
        new DefaultDirectoryTraverser(),
        transitiveClasspathEntries);

    // If we're packaging native libraries, construct the rule to build the fat JAR, which packages
    // up the original binary JAR and any required native libraries.
    if (!nativeLibraries.isEmpty()) {
      BuildRule innerJarRule = rule;
      resolver.addToIndex(innerJarRule);
      SourcePath innerJar = new BuildTargetSourcePath(innerJarRule.getBuildTarget());
      rule = new JarFattener(
          params.appendExtraDeps(
              Suppliers.<Iterable<BuildRule>>ofInstance(
                  pathResolver.filterBuildRuleInputs(
                      ImmutableList.<SourcePath>builder()
                          .add(innerJar)
                          .addAll(nativeLibraries.values())
                          .build()))),
          pathResolver,
          javacOptions,
          innerJar,
          nativeLibraries);
    }

    return rule;
  }

  @SuppressFieldNotInitialized
  public static class Args {
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<String> mainClass;
    public Optional<SourcePath> manifestFile;
    public Optional<Boolean> mergeManifests;
    @Beta
    public Optional<Path> metaInfDirectory;
    @Beta
    public Optional<ImmutableSortedSet<String>> blacklist;
  }
}
