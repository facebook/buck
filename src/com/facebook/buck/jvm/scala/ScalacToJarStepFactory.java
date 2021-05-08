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
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.file.FileExtensionMatcher;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BuildContextAwareExtraParams;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

/** Factory that creates Scala related compile build steps. */
public class ScalacToJarStepFactory extends CompileToJarStepFactory<BuildContextAwareExtraParams> {

  private static final PathMatcher JAVA_PATH_MATCHER = FileExtensionMatcher.of("java");
  private static final PathMatcher SCALA_PATH_MATCHER = FileExtensionMatcher.of("scala");

  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final Tool scalac;
  @AddToRuleKey private final ImmutableList<String> configCompilerFlags;
  @AddToRuleKey private final ImmutableList<String> extraArguments;
  @AddToRuleKey private final ImmutableSet<SourcePath> compilerPlugins;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  public ScalacToJarStepFactory(
      Tool scalac,
      ImmutableList<String> configCompilerFlags,
      ImmutableList<String> extraArguments,
      ImmutableSet<BuildRule> compilerPlugins,
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClassPath,
      boolean withDownwardApi) {
    super(CompileToJarStepFactory.hasAnnotationProcessing(javacOptions), withDownwardApi);
    this.javacOptions = javacOptions;
    this.scalac = scalac;
    this.configCompilerFlags = configCompilerFlags;
    this.extraArguments = extraArguments;
    this.compilerPlugins =
        compilerPlugins.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(ImmutableSet.toImmutableSet());
    this.extraClasspathProvider = extraClassPath;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      BuildContextAwareExtraParams extraParams) {

    ImmutableSortedSet<RelPath> classpathEntries = parameters.getClasspathEntries();
    ImmutableSortedSet<RelPath> sourceFilePaths = parameters.getSourceFilePaths();
    RelPath outputDirectory = parameters.getOutputPaths().getClassesDir();

    AbsPath rootPath = getRootPath(filesystemParams);
    BuildContext context = extraParams.getBuildContext();
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();

    if (sourceFilePaths.stream().anyMatch(SCALA_PATH_MATCHER::matches)) {

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
                  .addAll(
                      RichStream.from(classpathEntries)
                          .map(rootPath::resolve)
                          .map(AbsPath::normalize)
                          .map(AbsPath::getPath)
                          .iterator())
                  .build(),
              rootPath,
              ProjectFilesystemUtils.relativize(rootPath, context.getBuildCellRootPath()),
              withDownwardApi));
    }

    ImmutableSortedSet<RelPath> javaSourceFiles =
        sourceFilePaths.stream()
            .filter(JAVA_PATH_MATCHER::matches)
            .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));

    // Don't invoke javac if we don't have any java files.
    if (!javaSourceFiles.isEmpty()) {
      CompilerParameters javacParameters =
          CompilerParameters.builder()
              .from(parameters)
              .setClasspathEntries(
                  ImmutableSortedSet.orderedBy(RelPath.comparator())
                      .add(outputDirectory)
                      .addAll(
                          RichStream.from(extraClasspathProvider.getExtraClasspath())
                              .map(rootPath::relativize)
                              .iterator())
                      .addAll(classpathEntries)
                      .build())
              .setSourceFilePaths(javaSourceFiles)
              .build();

      JavacToJarStepFactory javacToJarStepFactory =
          new JavacToJarStepFactory(javacOptions, extraClasspathProvider, withDownwardApi);

      javacToJarStepFactory.createCompileStep(
          filesystemParams,
          cellToPathMappings,
          invokingRule,
          compilerOutputPathsValue,
          javacParameters,
          steps,
          buildableContext,
          resolvedJavac,
          javacToJarStepFactory.createExtraParams(sourcePathResolver, rootPath));
    }
  }
}
