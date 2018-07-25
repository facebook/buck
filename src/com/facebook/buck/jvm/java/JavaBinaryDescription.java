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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class JavaBinaryDescription
    implements DescriptionWithTargetGraph<JavaBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<JavaBinaryDescription.AbstractJavaBinaryDescriptionArg>,
        VersionRoot<JavaBinaryDescriptionArg> {

  private static final Flavor FAT_JAR_INNER_JAR_FLAVOR = InternalFlavor.of("inner-jar");

  private final ToolchainProvider toolchainProvider;
  private final JavaBuckConfig javaBuckConfig;

  public JavaBinaryDescription(ToolchainProvider toolchainProvider, JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<JavaBinaryDescriptionArg> getConstructorArgType() {
    return JavaBinaryDescriptionArg.class;
  }

  private CxxPlatform getCxxPlatform(AbstractJavaBinaryDescriptionArg args) {
    return args.getDefaultCxxPlatform()
        .map(
            toolchainProvider
                    .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                    .getCxxPlatforms()
                ::getValue)
        .orElse(
            toolchainProvider
                .getByName(JavaCxxPlatformProvider.DEFAULT_NAME, JavaCxxPlatformProvider.class)
                .getDefaultJavaCxxPlatform());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaBinaryDescriptionArg args) {

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    ImmutableMap<String, SourcePath> nativeLibraries =
        JavaLibraryRules.getNativeLibraries(
            params.getBuildDeps(), getCxxPlatform(args), context.getActionGraphBuilder());
    BuildTarget binaryBuildTarget = buildTarget;
    BuildRuleParams binaryParams = params;

    // If we're packaging native libraries, we'll build the binary JAR in a separate rule and
    // package it into the final fat JAR, so adjust it's params to use a flavored target.
    if (!nativeLibraries.isEmpty()) {
      binaryBuildTarget = binaryBuildTarget.withAppendedFlavors(FAT_JAR_INNER_JAR_FLAVOR);
    }

    JavaOptions javaOptions =
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptions();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Construct the build rule to build the binary JAR.
    ImmutableSet<JavaLibrary> transitiveClasspathDeps =
        JavaLibraryClasspathProvider.getClasspathDeps(binaryParams.getBuildDeps());
    ImmutableSet<SourcePath> transitiveClasspaths =
        JavaLibraryClasspathProvider.getClasspathsFromLibraries(transitiveClasspathDeps);
    BuildRule rule =
        new JavaBinary(
            binaryBuildTarget,
            projectFilesystem,
            binaryParams.copyAppendingExtraDeps(transitiveClasspathDeps),
            javaOptions.getJavaRuntimeLauncher(),
            args.getMainClass().orElse(null),
            args.getManifestFile().orElse(null),
            args.getMergeManifests().orElse(true),
            args.getDisallowAllDuplicates().orElse(false),
            args.getMetaInfDirectory().orElse(null),
            args.getBlacklist(),
            transitiveClasspathDeps,
            transitiveClasspaths,
            javaBuckConfig.shouldCacheBinaries(),
            javaBuckConfig.getDuplicatesLogLevel());

    // If we're packaging native libraries, construct the rule to build the fat JAR, which packages
    // up the original binary JAR and any required native libraries.
    if (!nativeLibraries.isEmpty()) {
      BuildRule innerJarRule = rule;
      graphBuilder.addToIndex(innerJarRule);
      SourcePath innerJar = innerJarRule.getSourcePathToOutput();
      rule =
          new JarFattener(
              buildTarget,
              projectFilesystem,
              params.copyAppendingExtraDeps(
                  Suppliers.<Iterable<BuildRule>>ofInstance(
                      ruleFinder.filterBuildRuleInputs(
                          ImmutableList.<SourcePath>builder()
                              .add(innerJar)
                              .addAll(nativeLibraries.values())
                              .build()))),
              JavacFactory.getDefault(toolchainProvider).create(ruleFinder, null),
              toolchainProvider
                  .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                  .getJavacOptions(),
              innerJar,
              nativeLibraries,
              javaOptions.getJavaRuntimeLauncher());
    }

    return rule;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractJavaBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getCxxPlatform(constructorArg)));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaBinaryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasTests {
    Optional<String> getMainClass();

    Optional<SourcePath> getManifestFile();

    Optional<Boolean> getMergeManifests();

    Optional<Boolean> getDisallowAllDuplicates();

    Optional<Path> getMetaInfDirectory();

    ImmutableSet<Pattern> getBlacklist();

    Optional<Flavor> getDefaultCxxPlatform();
  }
}
