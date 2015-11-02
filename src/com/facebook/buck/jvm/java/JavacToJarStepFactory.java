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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class JavacToJarStepFactory {
  // JavacStep parameters
  private Path outputDirectory;
  private Optional<Path> workingDirectory;
  private ImmutableSortedSet<Path> javaSourceFilePaths;
  private Optional<Path> pathToSrcsList;
  private ImmutableSortedSet<Path> declaredClasspathEntries;
  private JavacOptions javacOptions;
  private BuildTarget invokingRule;
  private Optional<JavacStep.SuggestBuildRules> suggestBuildRules;
  private SourcePathResolver resolver;
  private ProjectFilesystem filesystem;

  // JarDirectoryStep parameters
  private Path pathToOutputFile;
  private ImmutableSortedSet<Path> entriesToJar;
  @Nullable private String mainClass;
  @Nullable private Path manifestFile;

  private final List<Step> intermediateCommands;

  public JavacToJarStepFactory(Path outputDirectory,
      Optional<Path> workingDirectory,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      BuildTarget invokingRule,
      Optional<JavacStep.SuggestBuildRules> suggestBuildRules,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      ImmutableSortedSet<Path> entriesToJar,
      @Nullable String mainClass,
      @Nullable Path manifestFile,
      List<Step> intermediateCommands
  ) {
    // JavacStep parameters
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.javaSourceFilePaths = javaSourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.javacOptions = javacOptions;
    this.invokingRule = invokingRule;
    this.suggestBuildRules = suggestBuildRules;
    this.resolver = resolver;
    this.filesystem = filesystem;

    // JarDirectoryStep parameters
    this.pathToOutputFile = pathToOutputFile;
    this.entriesToJar = entriesToJar;
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;

    this.intermediateCommands = intermediateCommands;
  }

  void getJavacToJarStep(ImmutableList.Builder<Step> steps) {
    JavacStep javacStep = new JavacStep(
        outputDirectory,
        workingDirectory,
        javaSourceFilePaths,
        pathToSrcsList,
        declaredClasspathEntries,
        javacOptions.getJavac(),
        javacOptions,
        invokingRule,
        suggestBuildRules,
        resolver,
        filesystem);

    steps.add(javacStep);
    steps.addAll(Lists.newCopyOnWriteArrayList(intermediateCommands));
    steps.add(
        new JarDirectoryStep(
            filesystem,
            pathToOutputFile,
            entriesToJar,
            mainClass,
            manifestFile));
  }
}
