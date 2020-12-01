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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** Factory that creates Java related compile build steps. */
public class JavacToJarStepFactory extends CompileToJarStepFactory<JavaExtraParams> {

  private static final Logger LOG = Logger.get(JavacToJarStepFactory.class);

  @AddToRuleKey private final ExtraClasspathProvider extraClasspathProvider;

  public JavacToJarStepFactory(
      JavacOptions javacOptions,
      ExtraClasspathProvider extraClasspathProvider,
      boolean withDownwardApi) {
    super(javacOptions, withDownwardApi);
    this.extraClasspathProvider = extraClasspathProvider;
  }

  public JavacPipelineState createPipelineState(
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      boolean withDownwardApi,
      ResolvedJavac resolvedJavac,
      JavaExtraParams javaExtraParams) {

    return new JavacPipelineState(
        resolvedJavac,
        javaExtraParams.getResolvedJavacOptions(),
        invokingRule,
        new ClasspathChecker(),
        compilerParameters,
        abiJarParameters,
        libraryJarParameters,
        withDownwardApi);
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget invokingRule,
      CompilerParameters parameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      JavaExtraParams extraParams) {

    BaseBuckPaths buckPaths = filesystemParams.getBaseBuckPaths();
    addAnnotationGenFolderStep(invokingRule, steps, buildableContext, buckPaths);

    ResolvedJavacOptions resolvedJavacOptions = extraParams.getResolvedJavacOptions();
    steps.add(
        new JavacStep(
            resolvedJavac,
            resolvedJavacOptions,
            invokingRule,
            buckPaths,
            new ClasspathChecker(),
            parameters,
            null,
            null,
            withDownwardApi,
            cellToPathMappings));
  }

  /** Creates pipelined compile to jar steps and adds them into a {@code steps} builder */
  public void createPipelinedCompileToJarStep(
      FilesystemParams filesystemParams,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget target,
      JavacPipelineState pipeline,
      ImmutableList<String> postprocessClassesCommands,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ImmutableMap<RelPath, RelPath> resourcesMap) {
    Preconditions.checkArgument(postprocessClassesCommands.isEmpty());
    CompilerParameters compilerParameters = pipeline.getCompilerParameters();

    BaseBuckPaths buckPaths = filesystemParams.getBaseBuckPaths();
    AbsPath rootPath = filesystemParams.getRootPath();

    addAnnotationGenFolderStep(target, steps, buildableContext, buckPaths);

    if (!pipeline.isRunning()) {
      steps.addAll(getCompilerSetupIsolatedSteps(resourcesMap, rootPath, compilerParameters));
    }

    Optional<JarParameters> jarParameters =
        JavaAbis.isLibraryTarget(target)
            ? pipeline.getLibraryJarParameters()
            : pipeline.getAbiJarParameters();

    jarParameters.ifPresent(parameters -> addJarSetupSteps(parameters, steps));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(target, compilerParameters, buildableContext, buckPaths);

      // This adds the javac command, along with any supporting commands.
      createPipelinedCompileStep(buckPaths, cellToPathMappings, pipeline, target, steps);
    }

    jarParameters.ifPresent(
        parameters -> addJarCreationSteps(compilerParameters, steps, buildableContext, parameters));
  }

  private void addAnnotationGenFolderStep(
      BuildTarget invokingTarget,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      BaseBuckPaths buckPaths) {
    RelPath annotationGenFolder = CompilerOutputPaths.getAnnotationPath(invokingTarget, buckPaths);

    steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
    buildableContext.recordArtifact(annotationGenFolder.getPath());
  }

  @Override
  protected Optional<String> getBootClasspath() {
    return getBuildTimeOptions().getBootclasspath();
  }

  @Override
  public void createCompileToJarStepImpl(
      FilesystemParams filesystemParams,
      ImmutableMap<String, RelPath> cellToPathMappings,
      BuildTarget invokingRule,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      Path buildCellRootPath,
      ResolvedJavac resolvedJavac,
      JavaExtraParams extraParams) {
    BaseBuckPaths buckPaths = filesystemParams.getBaseBuckPaths();

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
                && resolvedJavac instanceof Jsr199Javac.ResolvedJsr199Javac);

    LOG.info(
        "Target: %s SpoolMode: %s Expected SpoolMode: %s Postprocessing steps: %s",
        invokingRule.getBaseName(),
        (isSpoolingToJarEnabled) ? (SpoolMode.DIRECT_TO_JAR) : (SpoolMode.INTERMEDIATE_TO_DISK),
        spoolMode,
        postprocessClassesCommands.toString());

    if (isSpoolingToJarEnabled) {
      steps.add(
          new JavacStep(
              resolvedJavac,
              extraParams.getResolvedJavacOptions(),
              invokingRule,
              buckPaths,
              new ClasspathChecker(),
              compilerParameters,
              abiJarParameters,
              libraryJarParameters,
              withDownwardApi,
              cellToPathMappings));
    } else {
      super.createCompileToJarStepImpl(
          filesystemParams,
          cellToPathMappings,
          invokingRule,
          compilerParameters,
          postprocessClassesCommands,
          null,
          libraryJarParameters,
          steps,
          buildableContext,
          withDownwardApi,
          buildCellRootPath,
          resolvedJavac,
          extraParams);
    }
  }

  private void createPipelinedCompileStep(
      BaseBuckPaths buckPaths,
      ImmutableMap<String, RelPath> cellToPathMappings,
      JavacPipelineState pipeline,
      BuildTarget invokingRule,
      Builder<IsolatedStep> steps) {
    if (hasAnnotationProcessing() && pipeline.isRunning()) {
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

  private JavacOptions getBuildTimeOptions() {
    return javacOptions.withBootclasspathFromContext(extraClasspathProvider);
  }

  /** Creates {@link JavaExtraParams}. */
  public JavaExtraParams createExtraParams(SourcePathResolverAdapter resolver, AbsPath rootPath) {
    JavacOptions buildTimeOptions = getBuildTimeOptions();
    ResolvedJavacOptions resolvedJavacOptions =
        ResolvedJavacOptions.of(buildTimeOptions, resolver, rootPath);
    return JavaExtraParams.of(resolvedJavacOptions);
  }

  @Override
  public boolean supportsCompilationDaemon() {
    return true;
  }
}
