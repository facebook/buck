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

import static com.facebook.buck.jvm.java.JavacOptions.SpoolMode;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavacToJarStepFactory extends CompileToJarStepFactory implements AddsToRuleKey {

  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;
  @AddToRuleKey private final boolean withDownwardApi;

  public JavacToJarStepFactory(
      Javac javac,
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClasspathProvider,
      boolean withDownwardApi) {
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.extraClasspathProvider = extraClasspathProvider;
    this.withDownwardApi = withDownwardApi;
  }

  public JavacPipelineState createPipelineState(
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      boolean withDownwardApi,
      SourcePathResolverAdapter resolver,
      AbsPath rootCellRoot) {
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    return new JavacPipelineState(
        javac,
        ResolvedJavacOptions.of(buildTimeOptions, resolver, rootCellRoot),
        invokingRule,
        new ClasspathChecker(),
        compilerParameters,
        abiJarParameters,
        libraryJarParameters,
        withDownwardApi);
  }

  private static void addAnnotationGenFolderStep(
      BuildTarget invokingTarget,
      ProjectFilesystem filesystem,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext) {
    RelPath annotationGenFolder =
        CompilerOutputPaths.getAnnotationPath(invokingTarget, filesystem.getBuckPaths());

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
    buildableContext.recordArtifact(annotationGenFolder.getPath());
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
    JavacOptions buildTimeOptions =
        javacOptions.withBootclasspathFromContext(extraClasspathProvider);

    addAnnotationGenFolderStep(invokingRule, projectFilesystem, steps, buildableContext);

    AbsPath rootPath = projectFilesystem.getRootPath();
    steps.add(
        new JavacStep(
            javac,
            ResolvedJavacOptions.of(buildTimeOptions, context.getSourcePathResolver(), rootPath),
            invokingRule,
            projectFilesystem.getBuckPaths(),
            new ClasspathChecker(),
            parameters,
            null,
            null,
            withDownwardApi,
            cellToPathMappings));
  }

  /** Creates pipelined compile to jar steps and adds them into a {@code steps} builder */
  public void createPipelinedCompileToJarStep(
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget target,
      JavacPipelineState pipeline,
      ImmutableList<String> postprocessClassesCommands,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ImmutableMap<RelPath, RelPath> resourcesMap) {
    Preconditions.checkArgument(postprocessClassesCommands.isEmpty());
    CompilerParameters compilerParameters = pipeline.getCompilerParameters();

    addAnnotationGenFolderStep(target, projectFilesystem, steps, buildableContext);

    if (!pipeline.isRunning()) {
      steps.addAll(
          getCompilerSetupIsolatedSteps(
              resourcesMap, projectFilesystem.getRootPath(), compilerParameters));
    }

    Optional<JarParameters> jarParameters =
        JavaAbis.isLibraryTarget(target)
            ? pipeline.getLibraryJarParameters()
            : pipeline.getAbiJarParameters();

    jarParameters.ifPresent(parameters -> addJarSetupSteps(parameters, steps));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(projectFilesystem, target, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createPipelinedCompileStep(projectFilesystem, cellToPathMappings, pipeline, target, steps);
    }

    jarParameters.ifPresent(
        parameters -> addJarCreationSteps(compilerParameters, steps, buildableContext, parameters));
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
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildContext context,
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      /* output params */
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi) {
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
                && javacOptions.getSpoolMode() == JavacOptions.SpoolMode.DIRECT_TO_JAR
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
              ResolvedJavacOptions.of(
                  buildTimeOptions,
                  context.getSourcePathResolver(),
                  projectFilesystem.getRootPath()),
              invokingRule,
              projectFilesystem.getBuckPaths(),
              new ClasspathChecker(),
              compilerParameters,
              abiJarParameters,
              libraryJarParameters,
              withDownwardApi,
              cellToPathMappings));
    } else {
      super.createCompileToJarStepImpl(
          projectFilesystem,
          cellToPathMappings,
          context,
          invokingRule,
          compilerParameters,
          postprocessClassesCommands,
          null,
          libraryJarParameters,
          steps,
          buildableContext,
          withDownwardApi);
    }
  }

  public void createPipelinedCompileStep(
      ProjectFilesystem projectFilesystem,
      ImmutableMap<String, RelPath> cellToPathMappings,
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      Builder<IsolatedStep> steps) {
    boolean generatingCode = !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
    BuckPaths buckPaths = projectFilesystem.getBuckPaths();
    if (generatingCode && pipeline.isRunning()) {
      steps.add(
          SymlinkIsolatedStep.of(
              CompilerOutputPaths.getAnnotationPath(
                  JavaAbis.getSourceAbiJar(invokingRule), buckPaths),
              CompilerOutputPaths.getAnnotationPath(invokingRule, buckPaths)));
    }

    steps.add(new JavacStep(pipeline, invokingRule, buckPaths, cellToPathMappings));
  }

  @VisibleForTesting
  public JavacOptions getJavacOptions() {
    return javacOptions;
  }

  @Override
  public boolean hasAnnotationProcessing() {
    return !javacOptions.getJavaAnnotationProcessorParams().isEmpty();
  }
}
