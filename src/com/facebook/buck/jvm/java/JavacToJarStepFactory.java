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

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavacToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {
  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private JavacOptions javacOptions;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  public JavacToJarStepFactory(
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      Javac javac,
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClasspathProvider) {
    super(resolver, ruleFinder, projectFilesystem);
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.extraClasspathProvider = extraClasspathProvider;
  }

  public JavacPipelineState createPipelineState(
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters) {
    final JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    return new JavacPipelineState(
        javac,
        buildTimeOptions,
        invokingRule,
        resolver,
        projectFilesystem,
        new ClasspathChecker(),
        compilerParameters,
        abiJarParameters,
        libraryJarParameters);
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      /* output params */
      Builder<Step> steps,
      BuildableContext buildableContext) {
    final JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
    if (generatingCode) {
      // Javac requires that the root directory for generated sources already exist.
      addAnnotationGenFolderStep(
          CompilerParameters.getAnnotationPath(projectFilesystem, invokingRule).get(),
          projectFilesystem,
          steps,
          buildableContext,
          context,
          null);
    }

    steps.add(
        new JavacStep(
            javac,
            buildTimeOptions,
            invokingRule,
            resolver,
            projectFilesystem,
            new ClasspathChecker(),
            parameters,
            null,
            null));
  }

  @Override
  protected Optional<String> getBootClasspath(BuildContext context) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);
    return buildTimeOptions.getBootclasspath();
  }

  @Override
  public Tool getCompiler() {
    return javac;
  }

  @Override
  public Iterable<BuildRule> getExtraDeps(SourcePathRuleFinder ruleFinder) {
    // If any dep of an annotation processor changes, we need to recompile, so we add those as
    // extra deps
    return Iterables.concat(
        super.getExtraDeps(ruleFinder),
        ruleFinder.filterBuildRuleInputs(javacOptions.getAnnotationProcessingParams().getInputs()));
  }

  public final void createPipelinedCompileToJarStep(
      BuildContext context,
      BuildTarget target,
      JavacPipelineState pipeline,
      ResourcesParameters resourcesParameters,
      ImmutableList<String> postprocessClassesCommands,
      Builder<Step> steps,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(postprocessClassesCommands.isEmpty());
    CompilerParameters compilerParameters = pipeline.getCompilerParameters();

    if (!pipeline.isRunning()) {
      addCompilerSetupSteps(context, target, compilerParameters, resourcesParameters, steps);
    }

    Optional<JarParameters> jarParameters =
        HasJavaAbi.isLibraryTarget(target)
            ? pipeline.getLibraryJarParameters()
            : pipeline.getAbiJarParameters();

    if (jarParameters.isPresent()) {
      addJarSetupSteps(context, jarParameters.get(), steps);
    }

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(target, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createPipelinedCompileStep(context, pipeline, target, steps, buildableContext);
    }

    if (jarParameters.isPresent()) {
      addJarCreationSteps(compilerParameters, steps, buildableContext, jarParameters.get());
    }
  }

  @Override
  public void createCompileToJarStepImpl(
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
                .contains(compilerParameters.getOutputDirectory()));

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
      final JavacOptions buildTimeOptions =
          javacOptions.withBootclasspathFromContext(extraClasspathProvider);
      boolean generatingCode = !buildTimeOptions.getAnnotationProcessingParams().isEmpty();
      if (generatingCode) {
        // Javac requires that the root directory for generated sources already exists.
        addAnnotationGenFolderStep(
            CompilerParameters.getAnnotationPath(projectFilesystem, invokingRule).get(),
            projectFilesystem,
            steps,
            buildableContext,
            context,
            null);
      }

      steps.add(
          new JavacStep(
              javac,
              buildTimeOptions,
              invokingRule,
              resolver,
              projectFilesystem,
              new ClasspathChecker(),
              compilerParameters,
              abiJarParameters,
              libraryJarParameters));
    } else {
      super.createCompileToJarStepImpl(
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
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      Builder<Step> steps,
      BuildableContext buildableContext) {
    boolean generatingCode = !javacOptions.getAnnotationProcessingParams().isEmpty();
    if (generatingCode) {
      // Javac requires that the root directory for generated sources already exist.
      addAnnotationGenFolderStep(
          CompilerParameters.getAnnotationPath(projectFilesystem, invokingRule).get(),
          projectFilesystem,
          steps,
          buildableContext,
          context,
          pipeline);

      if (pipeline.isRunning()) {
        steps.add(
            SymlinkFileStep.of(
                projectFilesystem,
                CompilerParameters.getAnnotationPath(
                        projectFilesystem, HasJavaAbi.getSourceAbiJar(invokingRule))
                    .get(),
                CompilerParameters.getAnnotationPath(projectFilesystem, invokingRule).get()));
      }
    }

    steps.add(new JavacStep(pipeline, invokingRule));
  }

  private static void addAnnotationGenFolderStep(
      Path annotationGenFolder,
      ProjectFilesystem filesystem,
      Builder<Step> steps,
      BuildableContext buildableContext,
      BuildContext buildContext,
      @Nullable JavacPipelineState pipeline) {
    if (pipeline == null || !pipeline.isRunning()) {
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), filesystem, annotationGenFolder)));
    }
    buildableContext.recordArtifact(annotationGenFolder);
  }

  @VisibleForTesting
  public JavacOptions getJavacOptions() {
    return javacOptions;
  }
}
