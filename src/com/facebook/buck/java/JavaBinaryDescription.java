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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.DefaultDirectoryTraverser;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaBinaryDescription implements Description<JavaBinaryDescription.Args> {

  public static final BuildRuleType TYPE = new BuildRuleType("java_binary");

  private static final Flavor FAT_JAR_INNER_JAR_FLAVOR = new Flavor("inner-jar");

  private final Javac javac;
  private final JavacOptions javacOptions;
  private final CxxPlatform cxxPlatform;

  public JavaBinaryDescription(
      Javac javac,
      JavacOptions javacOptions,
      CxxPlatform cxxPlatform) {
    this.javac = javac;
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

  /**
   * @return all the transitive native libraries we depend on, represented as a map from their
   *     system-specific library names to their {@link SourcePath} objects.
   */
  private ImmutableMap<String, SourcePath> getNativeLibraries(Iterable<BuildRule> deps) {
    final ImmutableMap.Builder<String, SourcePath> libraries = ImmutableMap.builder();

    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof JavaNativeLinkable) {
          JavaNativeLinkable linkable = (JavaNativeLinkable) rule;
          libraries.putAll(linkable.getSharedLibraries(cxxPlatform));
        }
        if (rule instanceof JavaNativeLinkable ||
            rule instanceof JavaLibrary) {
          return rule.getDeps();
        } else {
          return ImmutableSet.of();
        }
      }
    }.start();

    return libraries.build();
  }

  @Override
  public <A extends Args> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableMap<String, SourcePath> nativeLibraries = getNativeLibraries(params.getDeps());
    BuildRuleParams binaryParams = params;

    // If we're packaging native libraries, we'll build the binary JAR in a separate rule and
    // package it into the final fat JAR, so adjust it's params to use a flavored target.
    if (!nativeLibraries.isEmpty()) {
      binaryParams = params.copyWithChanges(
          params.getBuildRuleType(),
          BuildTargets.extendFlavoredBuildTarget(params.getBuildTarget(), FAT_JAR_INNER_JAR_FLAVOR),
          params.getDeclaredDeps(),
          params.getExtraDeps());
    }

    // Construct the build rule to build the binary JAR.
    BuildRule rule = new JavaBinary(
        binaryParams,
        pathResolver,
        args.mainClass.orNull(),
        args.manifestFile.orNull(),
        args.mergeManifests.or(true),
        args.metaInfDirectory.orNull(),
        args.blacklist.or(ImmutableSortedSet.<String>of()),
        new DefaultDirectoryTraverser());

    // If we're packaging native libraries, construct the rule to build the fat JAR, which packages
    // up the original binary JAR and any required native libraries.
    if (!nativeLibraries.isEmpty()) {
      BuildRule innerJarRule = rule;
      resolver.addToIndex(innerJarRule);
      SourcePath innerJar = new BuildTargetSourcePath(innerJarRule.getBuildTarget());
      rule = new JarFattener(
          params.appendExtraDeps(
              pathResolver.filterBuildRuleInputs(
                  ImmutableList.<SourcePath>builder()
                      .add(innerJar)
                      .addAll(nativeLibraries.values())
                      .build())),
          pathResolver,
          javac,
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
