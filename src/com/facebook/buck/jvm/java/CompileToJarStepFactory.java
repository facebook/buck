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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Provides a base implementation for post compile steps. */
public abstract class CompileToJarStepFactory implements ConfiguredCompiler {
  protected CompileToJarStepFactory() {}

  public final void createCompileToJarStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ResourcesParameters resourcesParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(libraryJarParameters != null || abiJarParameters == null);

    addCompilerSetupSteps(
        context, projectFilesystem, target, compilerParameters, resourcesParameters, steps);

    JarParameters jarParameters =
        abiJarParameters != null ? abiJarParameters : libraryJarParameters;
    if (jarParameters != null) {
      addJarSetupSteps(projectFilesystem, context, jarParameters, steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(projectFilesystem, target, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createCompileToJarStepImpl(
          projectFilesystem,
          context,
          target,
          compilerParameters,
          postprocessClassesCommands,
          abiJarParameters,
          libraryJarParameters,
          steps,
          buildableContext);
    }

    if (jarParameters != null) {
      addJarCreationSteps(
          projectFilesystem, compilerParameters, steps, buildableContext, jarParameters);
    }
  }

  protected void addCompilerSetupSteps(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ResourcesParameters resourcesParameters,
      Builder<Step> steps) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                projectFilesystem,
                compilerParameters.getOutputPaths().getClassesDir())));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                projectFilesystem,
                compilerParameters.getOutputPaths().getAnnotationPath())));

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                projectFilesystem,
                compilerParameters.getOutputPaths().getOutputJarDirPath())));

    // If there are resources, then link them to the appropriate place in the classes directory.
    steps.add(
        new CopyResourcesStep(
            projectFilesystem,
            context,
            target,
            resourcesParameters,
            compilerParameters.getOutputPaths().getClassesDir()));

    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  projectFilesystem,
                  compilerParameters.getOutputPaths().getPathToSourcesList().getParent())));

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  projectFilesystem,
                  compilerParameters.getOutputPaths().getWorkingDirectory())));
    }
  }

  protected void addJarSetupSteps(
      ProjectFilesystem projectFilesystem,
      BuildContext context,
      JarParameters jarParameters,
      Builder<Step> steps) {
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                projectFilesystem,
                jarParameters.getJarPath().getParent())));
  }

  protected void recordDepFileIfNecessary(
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      CompilerParameters compilerParameters,
      BuildableContext buildableContext) {
    if (compilerParameters.shouldTrackClassUsage()) {
      Path depFilePath = CompilerOutputPaths.getDepFilePath(buildTarget, filesystem);
      buildableContext.recordArtifact(depFilePath);
    }
  }

  protected void addJarCreationSteps(
      ProjectFilesystem projectFilesystem,
      CompilerParameters compilerParameters,
      Builder<Step> steps,
      BuildableContext buildableContext,
      JarParameters jarParameters) {
    // No source files, only resources
    if (compilerParameters.getSourceFilePaths().isEmpty()) {
      createJarStep(projectFilesystem, jarParameters, steps);
    }
    buildableContext.recordArtifact(jarParameters.getJarPath());
  }

  protected void createCompileToJarStepImpl(
      ProjectFilesystem projectFilesystem,
      BuildContext context,
      BuildTarget target,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(abiJarParameters == null);
    Preconditions.checkArgument(
        libraryJarParameters != null
            && libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    createCompileStep(
        context, projectFilesystem, target, compilerParameters, steps, buildableContext);

    steps.addAll(
        Lists.newCopyOnWriteArrayList(
            addPostprocessClassesCommands(
                projectFilesystem,
                postprocessClassesCommands,
                compilerParameters.getOutputPaths().getClassesDir(),
                compilerParameters.getClasspathEntries(),
                getBootClasspath(context))));

    createJarStep(projectFilesystem, libraryJarParameters, steps);
  }

  public void createJarStep(
      ProjectFilesystem projectFilesystem, JarParameters parameters, Builder<Step> steps) {
    steps.add(new JarDirectoryStep(projectFilesystem, parameters));
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

    for (String postprocessClassesCommand : postprocessClassesCommands) {
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

  public abstract void createCompileStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext);
}
