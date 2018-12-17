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

import static com.facebook.buck.jvm.java.AbstractJavacOptions.SpoolMode;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavacToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {
  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private JavacOptions javacOptions;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  public JavacToJarStepFactory(
      Javac javac, JavacOptions javacOptions, ExtraClasspathProvider extraClasspathProvider) {
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.extraClasspathProvider = extraClasspathProvider;
  }

  public JavacPipelineState createPipelineState(
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    return new JavacPipelineState(
        javac,
        buildTimeOptions,
        invokingRule,
        new ClasspathChecker(),
        compilerParameters,
        abiJarParameters,
        libraryJarParameters);
  }

  private static void addAnnotationGenFolderStep(
      BuildTarget invokingTarget,
      ProjectFilesystem filesystem,
      Builder<Step> steps,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    Path annotationGenFolder =
        CompilerOutputPaths.getAnnotationPath(filesystem, invokingTarget).get();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, annotationGenFolder)));
    buildableContext.recordArtifact(annotationGenFolder);
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    addAnnotationGenFolderStep(invokingRule, projectFilesystem, steps, buildableContext, context);

    steps.add(
        new JavacStep(
            javac,
            buildTimeOptions,
            invokingRule,
            context.getSourcePathResolver(),
            projectFilesystem,
            new ClasspathChecker(),
            parameters,
            null,
            null));
  }

  public final void createPipelinedCompileToJarStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      BuildTarget target,
      JavacPipelineState pipeline,
      ResourcesParameters resourcesParameters,
      ImmutableList<String> postprocessClassesCommands,
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(postprocessClassesCommands.isEmpty());
    CompilerParameters compilerParameters = pipeline.getCompilerParameters();

    addAnnotationGenFolderStep(target, projectFilesystem, steps, buildableContext, context);

    if (!pipeline.isRunning()) {
      addCompilerSetupSteps(
          context, projectFilesystem, target, compilerParameters, resourcesParameters, steps);
    }

    Optional<JarParameters> jarParameters =
        JavaAbis.isLibraryTarget(target)
            ? pipeline.getLibraryJarParameters()
            : pipeline.getAbiJarParameters();

    if (jarParameters.isPresent()) {
      addJarSetupSteps(projectFilesystem, context, jarParameters.get(), steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(projectFilesystem, target, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createPipelinedCompileStep(context, projectFilesystem, pipeline, target, steps);
    }

    if (jarParameters.isPresent()) {
      addJarCreationSteps(
          projectFilesystem, compilerParameters, steps, buildableContext, jarParameters.get());
    }
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);
    return buildTimeOptions.getBootclasspath();
  }

  @Override
  public void createCompileToJarStepImpl(
      ProjectFilesystem projectFilesystem,
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(
        libraryJarParameters == null
            || libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    String spoolMode = javacOptions.getSpoolMode().name();
    // In order to use direct spooling to the Jar:
    // (1) It must be enabled through a .buckconfig.
    // (2) The target must have 0 postprocessing steps.
    // (3) Tha compile API must be JSR 199.
    boolean isSpoolingToJarEnabled =
        compilerParameters.getAbiGenerationMode().isSourceAbi()
            || (postprocessClassesCommands.isEmpty()
                && javacOptions.getSpoolMode() == AbstractJavacOptions.SpoolMode.DIRECT_TO_JAR
                && javac instanceof Jsr199Javac);

    LOG.info(
        "Target: %s SpoolMode: %s Expected SpoolMode: %s Postprocessing steps: %s",
        invokingRule.getBaseName(),
        (isSpoolingToJarEnabled) ? (SpoolMode.DIRECT_TO_JAR) : (SpoolMode.INTERMEDIATE_TO_DISK),
        spoolMode,
        postprocessClassesCommands.toString());

    if (isSpoolingToJarEnabled) {
      JavacOptions buildTimeOptions =
          javacOptions.withBootclasspathFromContext(extraClasspathProvider);

      steps.add(
          new JavacStep(
              javac,
              buildTimeOptions,
              invokingRule,
              context.getSourcePathResolver(),
              projectFilesystem,
              new ClasspathChecker(),
              compilerParameters,
              abiJarParameters,
              libraryJarParameters));
    } else {
      super.createCompileToJarStepImpl(
          projectFilesystem,
          context,
          invokingRule,
          compilerParameters,
          postprocessClassesCommands,
          null,
          libraryJarParameters,
          steps,
          buildableContext);
    }
  }

  public void createPipelinedCompileStep(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      Builder<Step> steps) {
    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
    if (generatingCode && pipeline.isRunning()) {
      steps.add(
          SymlinkFileStep.of(
              projectFilesystem,
              CompilerOutputPaths.getAnnotationPath(
                      projectFilesystem, JavaAbis.getSourceAbiJar(invokingRule))
                  .get(),
              CompilerOutputPaths.getAnnotationPath(projectFilesystem, invokingRule).get()));
    }

    steps.add(
        new JavacStep(pipeline, invokingRule, context.getSourcePathResolver(), projectFilesystem));
  }

  @VisibleForTesting
  public JavacOptions getJavacOptions() {
    return javacOptions;
  }

  @Override
  public boolean hasAnnotationProcessing() {
    return !javacOptions.getAnnotationProcessingParams().isEmpty();
  }
}
