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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Provides a base implementation for post compile steps. */
public abstract class CompileToJarStepFactory implements AddsToRuleKey {

  protected CompileToJarStepFactory() {}

  public final void createCompileToJarStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      ImmutableMap<String, RelPath> cellToPathMappings,
      ImmutableMap<RelPath, RelPath> resourcesMap) {
    Preconditions.checkArgument(libraryJarParameters != null || abiJarParameters == null);

    steps.addAll(
        getCompilerSetupIsolatedSteps(
            resourcesMap, projectFilesystem.getRootPath(), compilerParameters));

    JarParameters jarParameters =
        abiJarParameters != null ? abiJarParameters : libraryJarParameters;
    if (jarParameters != null) {
      addJarSetupSteps(jarParameters, steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(projectFilesystem, target, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createCompileToJarStepImpl(
          projectFilesystem,
          cellToPathMappings,
          context,
          target,
          compilerParameters,
          postprocessClassesCommands,
          abiJarParameters,
          libraryJarParameters,
          steps,
          buildableContext,
          withDownwardApi);
    }

    if (jarParameters != null) {
      addJarCreationSteps(compilerParameters, steps, buildableContext, jarParameters);
    }
  }

  /** Returns Compiler Setup steps */
  protected ImmutableList<IsolatedStep> getCompilerSetupIsolatedSteps(
      ImmutableMap<RelPath, RelPath> resourcesMap,
      AbsPath rootCellRoot,
      CompilerParameters compilerParameters) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    CompilerOutputPaths outputPaths = compilerParameters.getOutputPaths();

    Builder<IsolatedStep> steps = ImmutableList.builder();

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getClassesDir()));
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(outputPaths.getAnnotationPath()));
    steps.add(MkdirIsolatedStep.of(getRelPath(rootCellRoot, outputPaths.getOutputJarDirPath())));

    // If there are resources, then link them to the appropriate place in the classes directory.
    steps.addAll(CopyResourcesStep.of(resourcesMap));

    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      steps.add(
          MkdirIsolatedStep.of(
              getRelPath(rootCellRoot, outputPaths.getPathToSourcesList().getParent())));
      steps.addAll(
          MakeCleanDirectoryIsolatedStep.of(
              getRelPath(rootCellRoot, outputPaths.getWorkingDirectory())));
    }

    return steps.build();
  }

  private RelPath getRelPath(AbsPath rootCellRoot, Path path) {
    return rootCellRoot.relativize(rootCellRoot.resolve(path));
  }

  protected void addJarSetupSteps(JarParameters jarParameters, Builder<IsolatedStep> steps) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(jarParameters.getJarPath().getParent()));
  }

  protected void recordDepFileIfNecessary(
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    if (compilerParameters.shouldTrackClassUsage()) {
      Path depFilePath = CompilerOutputPaths.getDepFilePath(buildTarget, filesystem.getBuckPaths());
      buildableContext.recordArtifact(depFilePath);
    }
  }

  protected void addJarCreationSteps(
      CompilerParameters compilerParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      JarParameters jarParameters) {
    // No source files, only resources
    if (compilerParameters.getSourceFilePaths().isEmpty()) {
      createJarStep(jarParameters, steps);
    }
    buildableContext.recordArtifact(jarParameters.getJarPath().getPath());
  }

  protected void createCompileToJarStepImpl(
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildContext context,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi) {
    Preconditions.checkArgument(abiJarParameters == null);
    Preconditions.checkArgument(
        libraryJarParameters != null
            && libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    createCompileStep(
        context,
        projectFilesystem,
        cellToPathMappings,
        target,
        compilerParameters,
        steps,
        buildableContext);

    steps.addAll(
        addPostprocessClassesCommands(
            projectFilesystem,
            postprocessClassesCommands,
            compilerParameters.getOutputPaths().getClassesDir(),
            compilerParameters.getClasspathEntries(),
            getBootClasspath(context),
            withDownwardApi,
            context.getBuildCellRootPath()));

    createJarStep(libraryJarParameters, steps);
  }

  public void createJarStep(JarParameters parameters, Builder<IsolatedStep> steps) {
    steps.add(new JarDirectoryStep(parameters));
  }

  /**
   * This can be used make the bootclasspath if available, to the postprocess classes commands.
   *
   * @return the bootclasspath.
   */
  @SuppressWarnings("unused")
  protected Optional<String> getBootClasspath(BuildContext context) {
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
  static ImmutableList<IsolatedStep> addPostprocessClassesCommands(
      ProjectFilesystem filesystem,
      List<String> postprocessClassesCommands,
      RelPath outputDirectory,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Optional<String> bootClasspath,
      boolean withDownwardApi,
      Path buildCellRootPath) {
    if (postprocessClassesCommands.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<IsolatedStep> commands = new ImmutableList.Builder<>();
    ImmutableMap<String, String> envVars =
        getEnvs(filesystem, declaredClasspathEntries, bootClasspath);

    AbsPath rootPath = filesystem.getRootPath();
    RelPath cellPath = ProjectFilesystemUtils.relativize(rootPath, buildCellRootPath);
    for (String postprocessClassesCommand : postprocessClassesCommands) {
      String bashCommand = postprocessClassesCommand + " " + outputDirectory;
      commands.add(
          new BashStep(rootPath, cellPath, withDownwardApi, bashCommand) {

            @Override
            public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
              return envVars;
            }
          });
    }
    return commands.build();
  }

  private static ImmutableMap<String, String> getEnvs(
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Optional<String> bootClasspath) {
    ImmutableMap.Builder<String, String> envVarBuilder = ImmutableMap.builder();
    envVarBuilder.put(
        "COMPILATION_CLASSPATH",
        Joiner.on(':').join(Iterables.transform(declaredClasspathEntries, filesystem::resolve)));

    bootClasspath.ifPresent(s -> envVarBuilder.put("COMPILATION_BOOTCLASSPATH", s));
    return envVarBuilder.build();
  }

  public abstract void createCompileStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext);

  public abstract boolean hasAnnotationProcessing();
}
