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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;

public class ScalacToJarStepFactory extends BaseCompileToJarStepFactory implements AddsToRuleKey {

  @AddToRuleKey private final Tool scalac;
  private final BuildRule scalaLibraryTarget;
  @AddToRuleKey private final ImmutableList<String> configCompilerFlags;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  @AddToRuleKey private final ImmutableSet<SourcePath> compilerPlugins;
  private final Function<BuildContext, Iterable<Path>> extraClassPath;

  public ScalacToJarStepFactory(
      Tool scalac,
      BuildRule scalaLibraryTarget,
      ImmutableList<String> configCompilerFlags,
      ImmutableList<String> extraArguments,
      ImmutableSet<BuildRule> compilerPlugins) {
    this(
        scalac,
        scalaLibraryTarget,
        configCompilerFlags,
        extraArguments,
        compilerPlugins,
        EMPTY_EXTRA_CLASSPATH);
  }

  public ScalacToJarStepFactory(
      Tool scalac,
      BuildRule scalaLibraryTarget,
      ImmutableList<String> configCompilerFlags,
      ImmutableList<String> extraArguments,
      ImmutableSet<BuildRule> compilerPlugins,
      Function<BuildContext, Iterable<Path>> extraClassPath) {
    this.scalac = scalac;
    this.scalaLibraryTarget = scalaLibraryTarget;
    this.configCompilerFlags = configCompilerFlags;
    this.extraArguments = extraArguments;
    this.compilerPlugins =
        compilerPlugins
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSet());
    this.extraClassPath = extraClassPath;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> classpathEntries,
      Path outputDirectory,
      Optional<Path> generatedCodeDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> depFilePath,
      Path pathToSrcsList,
      /* out params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {

    steps.add(
        new ScalacStep(
            scalac,
            ImmutableList.<String>builder()
                .addAll(configCompilerFlags)
                .addAll(extraArguments)
                .addAll(
                    Iterables.transform(
                        compilerPlugins,
                        input ->
                            "-Xplugin:"
                                + context
                                    .getSourcePathResolver()
                                    .getRelativePath(input)
                                    .toString()))
                .build(),
            resolver,
            outputDirectory,
            sourceFilePaths,
            ImmutableSortedSet.<Path>naturalOrder()
                .addAll(
                    Optional.ofNullable(extraClassPath.apply(context)).orElse(ImmutableList.of()))
                .addAll(classpathEntries)
                .build(),
            filesystem));
  }

  @Override
  protected Tool getCompiler() {
    return scalac;
  }

  @Override
  public Iterable<BuildRule> getDeclaredDeps(SourcePathRuleFinder ruleFinder) {
    return Iterables.concat(
        super.getDeclaredDeps(ruleFinder), ImmutableList.of(scalaLibraryTarget));
  }
}
