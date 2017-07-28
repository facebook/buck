/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

public class KotlincToJarStepFactory extends BaseCompileToJarStepFactory implements AddsToRuleKey {

  private static final PathOrGlobMatcher JAVA_PATH_MATCHER = new PathOrGlobMatcher("**.java");

  @AddToRuleKey private final Kotlinc kotlinc;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  private final Function<BuildContext, Iterable<Path>> extraClassPath;
  private final Javac javac;
  private final JavacOptions javacOptions;
  private final JavacOptionsAmender amender;

  public KotlincToJarStepFactory(
      Kotlinc kotlinc,
      ImmutableList<String> extraArguments,
      Function<BuildContext, Iterable<Path>> extraClassPath,
      Javac javac,
      JavacOptions javacOptions,
      JavacOptionsAmender amender) {
    this.kotlinc = kotlinc;
    this.extraArguments = extraArguments;
    this.extraClassPath = extraClassPath;
    this.javac = javac;
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.amender = amender;
  }

  @Override
  public void createCompileStep(
      BuildContext buildContext,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> generatedCodeDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> depFilePath,
      Path pathToSrcsList,
      /* out params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {

    steps.add(
        new KotlincStep(
            invokingRule,
            outputDirectory,
            sourceFilePaths,
            pathToSrcsList,
            ImmutableSortedSet.<Path>naturalOrder()
                .addAll(
                    Optional.ofNullable(extraClassPath.apply(buildContext))
                        .orElse(ImmutableList.of()))
                .addAll(declaredClasspathEntries)
                .build(),
            kotlinc,
            extraArguments,
            filesystem));

    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sourceFilePaths
                .stream()
                .filter(JAVA_PATH_MATCHER::matches)
                .collect(Collectors.toSet()));

    // Don't invoke javac if we don't have any java files.
    if (!javaSourceFiles.isEmpty()) {
      new JavacToJarStepFactory(javac, javacOptions, amender)
          .createCompileStep(
              buildContext,
              javaSourceFiles,
              invokingRule,
              resolver,
              filesystem,
              // We need to add the kotlin class files to the classpath. (outputDirectory).
              ImmutableSortedSet.<Path>naturalOrder()
                  .add(outputDirectory)
                  .addAll(
                      Optional.ofNullable(extraClassPath.apply(buildContext))
                          .orElse(ImmutableList.of()))
                  .addAll(declaredClasspathEntries)
                  .build(),
              outputDirectory,
              generatedCodeDirectory,
              workingDirectory,
              depFilePath,
              pathToSrcsList,
              steps,
              buildableContext);
    }
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    JavacOptions buildTimeOptions = amender.amend(javacOptions, context);
    return buildTimeOptions.getBootclasspath();
  }

  @Override
  protected Tool getCompiler() {
    return kotlinc;
  }
}
