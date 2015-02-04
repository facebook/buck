/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.io.DirectoryTraverser;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import javax.annotation.Nullable;

@BuildsAnnotationProcessor
public class JavaBinary extends AbstractBuildRule implements BinaryBuildRule, HasClasspathEntries {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  @Nullable
  private final String mainClass;

  @Nullable
  private final SourcePath manifestFile;
  private final boolean mergeManifests;

  @Nullable
  private final Path metaInfDirectory;
  private final ImmutableSet<String> blacklist;

  private final DirectoryTraverser directoryTraverser;

  public JavaBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      @Nullable String mainClass,
      @Nullable SourcePath manifestFile,
      boolean mergeManifests,
      @Nullable Path metaInfDirectory,
      ImmutableSet<String> blacklist,
      DirectoryTraverser directoryTraverser) {
    super(params, resolver);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.mergeManifests = mergeManifests;
    this.metaInfDirectory = metaInfDirectory;
    this.blacklist = blacklist;

    this.directoryTraverser = directoryTraverser;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("mainClass", mainClass)
        .setReflectively("blacklist", blacklist);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    // Build a sorted set so that metaInfDirectory contents are listed in a canonical order.
    ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();

    if (manifestFile != null) {
      builder.addAll(
          getResolver().filterInputsToCompareToOutput(Collections.singleton(manifestFile)));
    }

    BuildRules.addInputsToSortedSet(metaInfDirectory, builder, directoryTraverser);

    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    Path outputDirectory = getOutputDirectory();
    Step mkdir = new MkdirStep(outputDirectory);
    commands.add(mkdir);

    ImmutableSet<Path> includePaths;
    if (metaInfDirectory != null) {
      Path stagingRoot = outputDirectory.resolve("meta_inf_staging");
      Path stagingTarget = stagingRoot.resolve("META-INF");

      MakeCleanDirectoryStep createStagingRoot = new MakeCleanDirectoryStep(stagingRoot);
      commands.add(createStagingRoot);

      MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(
          metaInfDirectory, stagingTarget);
      commands.add(link);

      includePaths = ImmutableSet.<Path>builder()
          .add(stagingRoot)
          .addAll(getTransitiveClasspathEntries().values())
          .build();
    } else {
      includePaths = ImmutableSet.copyOf(getTransitiveClasspathEntries().values());
    }

    Path outputFile = getPathToOutputFile();
    Path manifestPath = manifestFile == null ? null : getResolver().getPath(manifestFile);
    Step jar = new JarDirectoryStep(
        outputFile,
        includePaths,
        mainClass,
        manifestPath,
        mergeManifests,
        blacklist);
    commands.add(jar);

    buildableContext.recordArtifact(outputFile);
    return commands.build();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return Classpaths.getClasspathEntries(getDeps());
  }

  private Path getOutputDirectory() {
    return Paths.get(String.format("%s/%s", BuckConstant.GEN_DIR, getBuildTarget().getBasePath()));
  }

  @Override
  public Path getPathToOutputFile() {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputDirectory(),
            getBuildTarget().getShortNameAndFlavorPostfix()));
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    Preconditions.checkState(
        mainClass != null,
        "Must specify a main class for %s in order to to run it.",
        getBuildTarget());

    return ImmutableList.of("java", "-jar",
        projectFilesystem.getAbsolutifier().apply(getPathToOutputFile()).toString());
  }
}
