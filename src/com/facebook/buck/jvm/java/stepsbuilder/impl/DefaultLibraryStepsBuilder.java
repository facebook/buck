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

package com.facebook.buck.jvm.java.stepsbuilder.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.FilesystemParamsUtils;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilder;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;

/** Default implementation of {@link LibraryStepsBuilder} */
class DefaultLibraryStepsBuilder<T extends CompileToJarStepFactory.ExtraParams>
    extends DefaultJavaStepsBuilderBase<T> implements LibraryStepsBuilder {

  DefaultLibraryStepsBuilder(CompileToJarStepFactory<T> configuredCompiler) {
    super(configuredCompiler);
  }

  @Override
  public void addUnusedDependencyStep(
      UnusedDependenciesParams unusedDependenciesParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      String buildTargetFullyQualifiedName) {
    String buildozerPath = unusedDependenciesParams.getBuildozerPath();
    stepsBuilder.add(
        UnusedDependenciesFinder.of(
            buildTargetFullyQualifiedName,
            unusedDependenciesParams.getDepsList(),
            unusedDependenciesParams.getProvidedDepsList(),
            unusedDependenciesParams.getExportedDepsList(),
            unusedDependenciesParams.getUnusedDependenciesAction(),
            buildozerPath.isEmpty() ? Optional.empty() : Optional.of(buildozerPath),
            unusedDependenciesParams.getOnlyPrintCommands(),
            cellToPathMappings,
            unusedDependenciesParams.getDepFileList().stream()
                .map(path -> RelPath.get(path.getPath()))
                .collect(ImmutableList.toImmutableList()),
            unusedDependenciesParams.getDoUltralightChecking()));
  }

  @Override
  public void addMakeMissingOutputsStep(
      RelPath rootOutput, RelPath pathToClassHashes, RelPath annotationsPath) {
    stepsBuilder.add(new MakeMissingOutputsStep(rootOutput, pathToClassHashes, annotationsPath));
  }

  @Override
  public void addBuildStepsForLibrary(
      AbiGenerationMode abiCompatibilityMode,
      AbiGenerationMode abiGenerationMode,
      boolean isRequiredForSourceOnlyAbi,
      ImmutableList<String> postprocessClassesCommands,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      boolean withDownwardApi,
      FilesystemParams filesystemParams,
      BuildableContext buildableContext,
      BuildTargetValue buildTargetValue,
      CompilerOutputPathsValue compilerOutputPathsValue,
      RelPath pathToClassHashes,
      ImmutableSortedSet<RelPath> compileTimeClasspathPaths,
      ImmutableSortedSet<RelPath> javaSrcs,
      ImmutableList<BaseJavaAbiInfo> fullJarInfos,
      ImmutableList<BaseJavaAbiInfo> abiJarInfos,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      @Nullable JarParameters libraryJarParameters,
      AbsPath buildCellRootPath,
      Optional<RelPath> pathToClasses,
      ResolvedJavac resolvedJavac,
      CompileToJarStepFactory.ExtraParams extraParams) {

    CompilerParameters compilerParameters =
        JavaLibraryRules.getCompilerParameters(
            compileTimeClasspathPaths,
            javaSrcs,
            fullJarInfos,
            abiJarInfos,
            buildTargetValue.getFullyQualifiedName(),
            trackClassUsage,
            trackJavacPhaseEvents,
            abiGenerationMode,
            abiCompatibilityMode,
            isRequiredForSourceOnlyAbi,
            compilerOutputPathsValue.getByType(buildTargetValue.getType()));

    Class<T> extraParamsType = configuredCompiler.getExtraParamsType();
    configuredCompiler.createCompileToJarStep(
        filesystemParams,
        buildTargetValue,
        compilerOutputPathsValue,
        compilerParameters,
        postprocessClassesCommands,
        null,
        libraryJarParameters,
        stepsBuilder,
        buildableContext,
        withDownwardApi,
        cellToPathMappings,
        resourcesMap,
        buildCellRootPath,
        resolvedJavac,
        extraParamsType.cast(extraParams));

    JavaLibraryRules.addAccumulateClassNamesStep(
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams),
        stepsBuilder,
        pathToClasses,
        pathToClassHashes);
  }
}
