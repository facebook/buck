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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Provides a base implementation for post compile steps. */
public abstract class BaseCompileToJarStepFactory implements CompileToJarStepFactory {

  public static final Function<BuildContext, Iterable<Path>> EMPTY_EXTRA_CLASSPATH =
      input -> ImmutableList.of();

  @Override
  public Iterable<BuildRule> getDeclaredDeps(SourcePathRuleFinder ruleFinder) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<BuildRule> getExtraDeps(SourcePathRuleFinder ruleFinder) {
    return getCompiler().getDeps(ruleFinder);
  }

  protected abstract Tool getCompiler();

  @Override
  public void createCompileToJarStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Path pathToSrcsList,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      Path outputJar,
      ClassUsageFileWriter usedClassesFileWriter,
      /* output params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      ImmutableSet<Pattern> classesToRemoveFromJar) {

    createCompileStep(
        context,
        sourceFilePaths,
        invokingRule,
        resolver,
        ruleFinder,
        filesystem,
        declaredClasspathEntries,
        outputDirectory,
        workingDirectory,
        pathToSrcsList,
        usedClassesFileWriter,
        steps,
        buildableContext);

    steps.addAll(
        Lists.newCopyOnWriteArrayList(
            addPostprocessClassesCommands(
                filesystem,
                postprocessClassesCommands,
                outputDirectory,
                declaredClasspathEntries,
                getBootClasspath(context))));

    createJarStep(
        filesystem,
        outputDirectory,
        mainClass,
        manifestFile,
        classesToRemoveFromJar,
        outputJar,
        steps);
  }

  @Override
  public void createJarStep(
      ProjectFilesystem filesystem,
      Path outputDirectory,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      ImmutableSet<Pattern> classesToRemoveFromJar,
      Path outputJar,
      ImmutableList.Builder<Step> steps) {
    steps.add(
        new JarDirectoryStep(
            filesystem,
            outputJar,
            ImmutableSortedSet.of(outputDirectory),
            mainClass.orElse(null),
            manifestFile.orElse(null),
            true,
            classesToRemoveFromJar));
  }

  /**
   * This can be used make the bootclasspath if available, to the postprocess classes commands.
   *
   * @return the bootclasspath.
   */
  @SuppressWarnings("unused")
  Optional<String> getBootClasspath(BuildContext context) {
    return Optional.empty();
  }

  /**
   * Adds a BashStep for each postprocessClasses command that runs the command followed by the
   * outputDirectory of javac outputs.
   *
   * <p>The expectation is that the command will inspect and update the directory by modifying,
   * adding, and deleting the .class files in the directory.
   *
   * <p>The outputDirectory should be a valid java root. I.e., if outputDirectory is
   * buck-out/bin/java/abc/lib__abc__classes/, then a contained class abc.AbcModule should be at
   * buck-out/bin/java/abc/lib__abc__classes/abc/AbcModule.class
   *
   * @param filesystem the project filesystem.
   * @param postprocessClassesCommands the list of commands to post-process .class files.
   * @param outputDirectory the directory that will contain all the javac output.
   * @param declaredClasspathEntries the list of classpath entries.
   * @param bootClasspath the compilation boot classpath.
   */
  @VisibleForTesting
  static ImmutableList<Step> addPostprocessClassesCommands(
      ProjectFilesystem filesystem,
      List<String> postprocessClassesCommands,
      Path outputDirectory,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Optional<String> bootClasspath) {
    if (postprocessClassesCommands.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> commands = new ImmutableList.Builder<Step>();
    ImmutableMap.Builder<String, String> envVarBuilder = ImmutableMap.builder();
    envVarBuilder.put(
        "COMPILATION_CLASSPATH",
        Joiner.on(':').join(Iterables.transform(declaredClasspathEntries, filesystem::resolve)));

    if (bootClasspath.isPresent()) {
      envVarBuilder.put("COMPILATION_BOOTCLASSPATH", bootClasspath.get());
    }
    ImmutableMap<String, String> envVars = envVarBuilder.build();

    for (final String postprocessClassesCommand : postprocessClassesCommands) {
      BashStep bashStep =
          new BashStep(
              filesystem.getRootPath(), postprocessClassesCommand + " " + outputDirectory) {
            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
              return envVars;
            }
          };
      commands.add(bashStep);
    }
    return commands.build();
  }
}
