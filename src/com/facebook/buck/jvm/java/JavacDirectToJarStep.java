/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A composite step used to compile java libraries directly to jar files retaining the intermediate
 * .class files in memory.
 */
public class JavacDirectToJarStep implements Step {
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final BuildTarget invokingRule;
  private final SourcePathResolver resolver;
  private final ProjectFilesystem filesystem;
  private final ImmutableSortedSet<Path> declaredClasspathEntries;
  private final Path outputDirectory;
  private final JavacOptions buildTimeOptions;
  private final Optional<Path> workingDirectory;
  private final Path pathToSrcsList;
  private final Optional<SuggestBuildRules> suggestBuildRules;
  private final ImmutableSortedSet<Path> entriesToJar;
  private final Optional<String> mainClass;
  private final Optional<Path> manifestFile;
  private final Path outputJar;
  private final ClassUsageFileWriter usedClassesFileWriter;

  public JavacDirectToJarStep(
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      JavacOptions buildTimeOptions,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Path pathToSrcsList,
      Optional<SuggestBuildRules> suggestBuildRules,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      Path outputJar,
      ClassUsageFileWriter usedClassesFileWriter) {
    this.sourceFilePaths = sourceFilePaths;
    this.invokingRule = invokingRule;
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.buildTimeOptions = buildTimeOptions;
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.pathToSrcsList = pathToSrcsList;
    this.suggestBuildRules = suggestBuildRules;
    this.entriesToJar = entriesToJar;
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.outputJar = outputJar;
    this.usedClassesFileWriter = usedClassesFileWriter;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    return createJavacStep().execute(context);
  }

  @Override
  public String getShortName() {
    return "javac_jar";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList<String> javacStepOptions = JavacStep.getOptions(
        buildTimeOptions,
        filesystem,
        outputDirectory,
        context,
        declaredClasspathEntries);
    String javacDescription = buildTimeOptions.getJavac().getDescription(
        javacStepOptions,
        sourceFilePaths,
        pathToSrcsList);

    String jarDescription = String.format("jar %s %s %s %s",
        getJarArgs(),
        outputJar,
        manifestFile.isPresent() ? manifestFile.get() : "",
        Joiner.on(' ').join(entriesToJar));

    return javacDescription + "; " + jarDescription;
  }

  private String getJarArgs() {
    String result = "cf";
    if (manifestFile.isPresent()) {
      result += "m";
    }
    return result;
  }

  private JavacStep createJavacStep() {
    DirectToJarOutputSettings directToJarOutputSettings = DirectToJarOutputSettings.of(
        outputJar,
        buildTimeOptions.getClassesToRemoveFromJar(),
        entriesToJar,
        mainClass,
        manifestFile);
    return new JavacStep(
        outputDirectory,
        usedClassesFileWriter,
        workingDirectory,
        sourceFilePaths,
        pathToSrcsList,
        declaredClasspathEntries,
        buildTimeOptions.getJavac(),
        buildTimeOptions,
        invokingRule,
        suggestBuildRules,
        resolver,
        filesystem,
        new ClasspathChecker(),
        Optional.of(directToJarOutputSettings));
  }
}
