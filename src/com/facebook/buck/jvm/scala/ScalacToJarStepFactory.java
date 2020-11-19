/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class ScalacToJarStepFactory extends CompileToJarStepFactory {

  private static final PathMatcher JAVA_PATH_MATCHER = FileExtensionMatcher.of("java");
  private static final PathMatcher SCALA_PATH_MATCHER = FileExtensionMatcher.of("scala");

  @AddToRuleKey private final Tool scalac;
  @AddToRuleKey private final ImmutableList<String> configCompilerFlags;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  @AddToRuleKey private final ImmutableSet<SourcePath> compilerPlugins;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;
  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final boolean withDownwardApi;

  public ScalacToJarStepFactory(
      Tool scalac,
      ImmutableList<String> configCompilerFlags,
      ImmutableList<String> extraArguments,
      ImmutableSet<BuildRule> compilerPlugins,
      Javac javac,
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClassPath,
      boolean withDownwardApi) {
    super(javacOptions);
    this.scalac = scalac;
    this.configCompilerFlags = configCompilerFlags;
    this.extraArguments = extraArguments;
    this.compilerPlugins =
        compilerPlugins.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSet.toImmutableSet());
    this.javac = javac;
    this.extraClasspathProvider = extraClassPath;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext) {

    ImmutableSortedSet<Path> classpathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<Path> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();

    if (sourceFilePaths.stream().anyMatch(SCALA_PATH_MATCHER::matches)) {
      AbsPath rootPath = projectFilesystem.getRootPath();

      SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
      ImmutableList<String> commandPrefix = scalac.getCommandPrefix(sourcePathResolver);
      ImmutableMap<String, String> environment = scalac.getEnvironment(sourcePathResolver);

      steps.add(
          new ScalacStep(
              commandPrefix,
              environment,
              ImmutableList.<String>builder()
                  .addAll(configCompilerFlags)
                  .addAll(extraArguments)
                  .addAll(
                      Iterables.transform(
                          compilerPlugins,
                          input -> "-Xplugin:" + sourcePathResolver.getCellUnsafeRelPath(input)))
                  .build(),
              outputDirectory.getPath(),
              sourceFilePaths,
              ImmutableSortedSet.<Path>naturalOrder()
                  .addAll(
                      RichStream.from(extraClasspathProvider.getExtraClasspath())
                          .map(AbsPath::getPath)
                          .iterator())
                  .addAll(classpathEntries)
                  .build(),
              rootPath,
              ProjectFilesystemUtils.relativize(rootPath, context.getBuildCellRootPath()),
              withDownwardApi));
    }

    ImmutableSortedSet<Path> javaSourceFiles =
        ImmutableSortedSet.copyOf(
            sourceFilePaths.stream()
                .filter(JAVA_PATH_MATCHER::matches)
                .collect(Collectors.toSet()));

    // Don't invoke javac if we don't have any java files.
    if (!javaSourceFiles.isEmpty()) {
      CompilerParameters javacParameters =
          CompilerParameters.builder()
              .from(parameters)
              .setClasspathEntries(
                  ImmutableSortedSet.<Path>naturalOrder()
                      .add(outputDirectory.getPath())
                      .addAll(
                          RichStream.from(extraClasspathProvider.getExtraClasspath())
                              .map(AbsPath::getPath)
                              .iterator())
                      .addAll(classpathEntries)
                      .build())
              .setSourceFilePaths(javaSourceFiles)
              .build();
      new JavacToJarStepFactory(javac, javacOptions, extraClasspathProvider, withDownwardApi)
          .createCompileStep(
              context,
              projectFilesystem,
              cellToPathMappings,
              invokingRule,
              javacParameters,
              steps,
              buildableContext);
    }
  }
}
