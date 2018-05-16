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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

public class ScalacToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {

  private static final PathOrGlobMatcher JAVA_PATH_MATCHER = new PathOrGlobMatcher("**.java");
  private static final PathOrGlobMatcher SCALA_PATH_MATCHER = new PathOrGlobMatcher("**.scala");

  @AddToRuleKey private final Tool scalac;
  private final BuildRule scalaLibraryTarget;
  @AddToRuleKey private final ImmutableList<String> configCompilerFlags;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  @AddToRuleKey private final ImmutableSet<SourcePath> compilerPlugins;
  @AddToRuleKey private final ExtraClasspathProvider extraClassPath;
  private final Javac javac;
  private final JavacOptions javacOptions;

  public ScalacToJarStepFactory(
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      Tool scalac,
      BuildRule scalaLibraryTarget,
      ImmutableList<String> configCompilerFlags,
      ImmutableList<String> extraArguments,
      ImmutableSet<BuildRule> compilerPlugins,
      Javac javac,
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClassPath) {
    super(resolver, ruleFinder, projectFilesystem);
    this.scalac = scalac;
    this.scalaLibraryTarget = scalaLibraryTarget;
    this.configCompilerFlags = configCompilerFlags;
    this.extraArguments = extraArguments;
    this.compilerPlugins =
        compilerPlugins
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSet.toImmutableSet());
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.extraClassPath = extraClassPath;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {

    ImmutableSortedSet<Path> classpathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    Path outputDirectory = parameters.getOutputDirectory();

    if (sourceFilePaths.stream().anyMatch(SCALA_PATH_MATCHER::matches)) {
      steps.add(
          new ScalacStep(
              invokingRule,
              scalac,
              ImmutableList.<String>builder()
                  .addAll(configCompilerFlags)
                  .addAll(extraArguments)
                  .addAll(
                      Iterables.transform(
                          compilerPlugins,
                          input ->
                              "-Xplugin:" + context.getSourcePathResolver().getRelativePath(input)))
                  .build(),
              resolver,
              outputDirectory,
              sourceFilePaths,
              ImmutableSortedSet.<Path>naturalOrder()
                  .addAll(
                      Optional.ofNullable(extraClassPath.getExtraClasspath())
                          .orElse(ImmutableList.of()))
                  .addAll(classpathEntries)
                  .build(),
              projectFilesystem));
    }

    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sourceFilePaths
                .stream()
                .filter(JAVA_PATH_MATCHER::matches)
                .collect(Collectors.toSet()));

    // Don't invoke javac if we don't have any java files.
    if (!javaSourceFiles.isEmpty()) {
      CompilerParameters javacParameters =
          CompilerParameters.builder()
              .from(parameters)
              .setClasspathEntries(
                  ImmutableSortedSet.<Path>naturalOrder()
                      .add(outputDirectory)
                      .addAll(
                          Optional.ofNullable(extraClassPath.getExtraClasspath())
                              .orElse(ImmutableList.of()))
                      .addAll(classpathEntries)
                      .build())
              .setSourceFilePaths(javaSourceFiles)
              .build();
      new JavacToJarStepFactory(
              resolver, ruleFinder, projectFilesystem, javac, javacOptions, extraClassPath)
          .createCompileStep(context, invokingRule, javacParameters, steps, buildableContext);
    }
  }

  @Override
  public Tool getCompiler() {
    return scalac;
  }

  @Override
  public Iterable<BuildRule> getDeclaredDeps(SourcePathRuleFinder ruleFinder) {
    return Iterables.concat(
        super.getDeclaredDeps(ruleFinder), ImmutableList.of(scalaLibraryTarget));
  }
}
