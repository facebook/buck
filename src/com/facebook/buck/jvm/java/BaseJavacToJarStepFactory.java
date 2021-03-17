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
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.abi.AbiGenerationModeUtils;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.SymlinkIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Base java implementation of compile to jar steps factory that doesn't depend on an internal build
 * graph datastructures.
 */
public class BaseJavacToJarStepFactory extends CompileToJarStepFactory<JavaExtraParams> {

  private static final Logger LOG = Logger.get(BaseJavacToJarStepFactory.class);

  @AddToRuleKey private final BuildJavaCommand.SpoolMode spoolMode;

  public BaseJavacToJarStepFactory(
      BuildJavaCommand.SpoolMode spoolMode,
      boolean hasAnnotationProcessing,
      boolean withDownwardApi) {
    super(hasAnnotationProcessing, withDownwardApi);
    this.spoolMode = spoolMode;
  }

  /** Creates pipeline state for java compilation. */
  public JavacPipelineState createPipelineState(
      BuildTargetValue invokingRule,
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
        compilerParameters,
        abiJarParameters,
        libraryJarParameters,
        withDownwardApi);
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      JavaExtraParams extraParams) {

    CompilerOutputPaths outputPath = compilerOutputPathsValue.getByType(invokingRule.getType());
    addAnnotationGenFolderStep(steps, buildableContext, outputPath.getAnnotationPath());

    ResolvedJavacOptions resolvedJavacOptions = extraParams.getResolvedJavacOptions();
    steps.add(
        new JavacStep(
            resolvedJavac,
            resolvedJavacOptions,
            invokingRule,
            getConfiguredBuckOut(filesystemParams),
            compilerOutputPathsValue,
            parameters,
            null,
            null,
            withDownwardApi,
            cellToPathMappings));
  }

  /** Creates pipelined compile to jar steps and adds them into a {@code steps} builder */
  public void createPipelinedCompileToJarStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue buildTargetValue,
      JavacPipelineState state,
      CompilerOutputPathsValue compilerOutputPathsValue,
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ImmutableMap<RelPath, RelPath> resourcesMap) {
    CompilerParameters compilerParameters = state.getCompilerParameters();

    CompilerOutputPaths outputPath = compilerOutputPathsValue.getByType(buildTargetValue.getType());
    addAnnotationGenFolderStep(steps, buildableContext, outputPath.getAnnotationPath());

    if (!state.isRunning()) {
      steps.addAll(getCompilerSetupIsolatedSteps(resourcesMap, compilerParameters));
    }

    Optional<JarParameters> jarParameters =
        buildTargetValue.isLibraryJar()
            ? state.getLibraryJarParameters()
            : state.getAbiJarParameters();

    jarParameters.ifPresent(parameters -> addJarSetupSteps(parameters, steps));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!compilerParameters.getSourceFilePaths().isEmpty()) {
      recordDepFileIfNecessary(
          compilerOutputPathsValue, buildTargetValue, compilerParameters, buildableContext);

      // This adds the javac command, along with any supporting commands.
      createPipelinedCompileStep(
          getConfiguredBuckOut(filesystemParams),
          compilerOutputPathsValue,
          cellToPathMappings,
          state,
          buildTargetValue,
          steps);
    }

    jarParameters.ifPresent(
        parameters -> addJarCreationSteps(compilerParameters, steps, buildableContext, parameters));
  }

  private void addAnnotationGenFolderStep(
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      RelPath annotationGenFolder) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
    buildableContext.recordArtifact(annotationGenFolder.getPath());
  }

  @Override
  public void createCompileToJarStepImpl(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters compilerParameters,
      ImmutableList<String> postprocessClassesCommands,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      boolean withDownwardApi,
      AbsPath buildCellRootPath,
      ResolvedJavac resolvedJavac,
      JavaExtraParams extraParams) {
    Preconditions.checkArgument(
        libraryJarParameters == null
            || libraryJarParameters
                .getEntriesToJar()
                .contains(compilerParameters.getOutputPaths().getClassesDir()));

    String spoolMode = this.spoolMode.name();
    // In order to use direct spooling to the Jar:
    // (1) It must be enabled through a .buckconfig.
    // (2) The target must have 0 postprocessing steps.
    // (3) Tha compile API must be JSR 199.
    boolean isSpoolingToJarEnabled =
        AbiGenerationModeUtils.isSourceAbi(compilerParameters.getAbiGenerationMode())
            || (postprocessClassesCommands.isEmpty()
                && this.spoolMode == BuildJavaCommand.SpoolMode.DIRECT_TO_JAR
                && resolvedJavac instanceof Jsr199Javac.ResolvedJsr199Javac);

    LOG.info(
        "Target: %s SpoolMode: %s Expected SpoolMode: %s Postprocessing steps: %s",
        invokingRule.getFullyQualifiedName(),
        (isSpoolingToJarEnabled)
            ? (BuildJavaCommand.SpoolMode.DIRECT_TO_JAR)
            : (BuildJavaCommand.SpoolMode.INTERMEDIATE_TO_DISK),
        spoolMode,
        postprocessClassesCommands.toString());

    if (isSpoolingToJarEnabled) {
      steps.add(
          new JavacStep(
              resolvedJavac,
              extraParams.getResolvedJavacOptions(),
              invokingRule,
              getConfiguredBuckOut(filesystemParams),
              compilerOutputPathsValue,
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
          compilerOutputPathsValue,
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
      RelPath configuredBuckOut,
      CompilerOutputPathsValue compilerOutputPathsValue,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      JavacPipelineState state,
      BuildTargetValue invokingRule,
      ImmutableList.Builder<IsolatedStep> steps) {
    if (hasAnnotationProcessing() && state.isRunning()) {
      CompilerOutputPaths outputPath = compilerOutputPathsValue.getByType(invokingRule.getType());
      steps.add(
          SymlinkIsolatedStep.of(
              compilerOutputPathsValue.getSourceAbiCompilerOutputPath().getAnnotationPath(),
              outputPath.getAnnotationPath()));
    }

    steps.add(
        new JavacStep(
            state, invokingRule, configuredBuckOut, compilerOutputPathsValue, cellToPathMappings));
  }

  @Override
  public boolean supportsCompilationDaemon() {
    return true;
  }

  public boolean isWithDownwardApi() {
    return withDownwardApi;
  }

  public BuildJavaCommand.SpoolMode getSpoolMode() {
    return spoolMode;
  }

  private RelPath getConfiguredBuckOut(FilesystemParams filesystemParams) {
    return RelPath.get(filesystemParams.getConfiguredBuckOut().getPath());
  }
}
