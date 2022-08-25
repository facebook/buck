/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin.cd;

import com.facebook.buck.cd.model.java.BaseJarCommand;
import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.cd.model.java.LibraryJarBaseCommand;
import com.facebook.buck.cd.model.kotlin.AbiJarCommand;
import com.facebook.buck.cd.model.kotlin.BaseCommandParams;
import com.facebook.buck.cd.model.kotlin.BuildKotlinCommand;
import com.facebook.buck.cd.model.kotlin.LibraryJarCommand;
import com.facebook.buck.core.build.buildable.context.NoOpBuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.cd.AbiStepsBuilder;
import com.facebook.buck.jvm.cd.DefaultCompileStepsBuilderFactory;
import com.facebook.buck.jvm.cd.LibraryStepsBuilder;
import com.facebook.buck.jvm.cd.serialization.AbsPathSerializer;
import com.facebook.buck.jvm.cd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.cd.serialization.java.BuildTargetValueSerializer;
import com.facebook.buck.jvm.cd.serialization.java.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.cd.serialization.java.JarParametersSerializer;
import com.facebook.buck.jvm.cd.serialization.java.JavaAbiInfoSerializer;
import com.facebook.buck.jvm.cd.serialization.java.ResolvedJavacSerializer;
import com.facebook.buck.jvm.cd.serialization.kotlin.KotlinExtraParamsSerializer;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.kotlin.DaemonKotlincToJarStepFactory;
import com.facebook.buck.jvm.kotlin.KotlinExtraParams;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** Converts serialized protobuf commands into steps to compile Kotlin targets. */
public class KotlinStepsBuilder {
  private final ImmutableList<IsolatedStep> steps;
  private final AbsPath ruleCellRoot;

  public KotlinStepsBuilder(BuildKotlinCommand buildKotlinCommand) {
    Pair<AbsPath, ImmutableList<IsolatedStep>> pair = buildSteps(buildKotlinCommand);
    ruleCellRoot = pair.getFirst();
    steps = pair.getSecond();
  }

  /** @return the steps corresponding to the protobuf command */
  public ImmutableList<IsolatedStep> getSteps() {
    return steps;
  }

  /** @return the rule cell root. */
  public AbsPath getRuleCellRoot() {
    return ruleCellRoot;
  }

  private Pair<AbsPath, ImmutableList<IsolatedStep>> buildSteps(
      BuildKotlinCommand buildKotlinCommand) {
    BaseCommandParams baseCommandParams = buildKotlinCommand.getBaseCommandParams();
    DaemonKotlincToJarStepFactory kotlincToJarStepFactory =
        new DaemonKotlincToJarStepFactory(
            baseCommandParams.getHasAnnotationProcessing(), baseCommandParams.getWithDownwardApi());

    DefaultCompileStepsBuilderFactory<KotlinExtraParams> stepsBuilderFactory =
        new DefaultCompileStepsBuilderFactory<>(kotlincToJarStepFactory);

    boolean withDownwardApi = buildKotlinCommand.getBaseCommandParams().getWithDownwardApi();

    AbsPath ruleCellRoot;
    ImmutableList<IsolatedStep> steps;

    switch (buildKotlinCommand.getCommandCase()) {
      case LIBRARYJARCOMMAND:
        LibraryJarCommand libraryJarCommand = buildKotlinCommand.getLibraryJarCommand();
        ruleCellRoot = getRootPath(libraryJarCommand.getBaseJarCommand());
        steps = handleLibaryJarCommand(stepsBuilderFactory, libraryJarCommand, withDownwardApi);
        break;

      case ABIJARCOMMAND:
        AbiJarCommand abiJarCommand = buildKotlinCommand.getAbiJarCommand();
        ruleCellRoot = getRootPath(abiJarCommand.getBaseJarCommand());
        steps = handleAbiJarCommand(stepsBuilderFactory, abiJarCommand, withDownwardApi);
        break;

      case COMMAND_NOT_SET:
      default:
        throw new IllegalStateException(buildKotlinCommand.getCommandCase() + " is not supported!");
    }

    return new Pair<>(ruleCellRoot, steps);
  }

  private AbsPath getRootPath(BaseJarCommand baseJarCommand) {
    return AbsPathSerializer.deserialize(baseJarCommand.getFilesystemParams().getRootPath());
  }

  private ImmutableList<IsolatedStep> handleLibaryJarCommand(
      DefaultCompileStepsBuilderFactory<KotlinExtraParams> stepsBuilderFactory,
      LibraryJarCommand libraryJarCommand,
      boolean withDownwardApi) {

    LibraryStepsBuilder libraryStepsBuilder = stepsBuilderFactory.getLibraryBuilder();

    BaseJarCommand baseJarCommand = libraryJarCommand.getBaseJarCommand();
    LibraryJarBaseCommand libraryJarBaseCommand = libraryJarCommand.getLibraryJarBaseCommand();

    FilesystemParams filesystemParam = baseJarCommand.getFilesystemParams();
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(baseJarCommand.getBuildTargetValue());
    RelPath pathToClassHashes =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes());
    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        RelPathSerializer.toCellToPathMapping(baseJarCommand.getCellToPathMappingsMap());

    libraryStepsBuilder.addBuildStepsForLibrary(
        baseJarCommand.getAbiCompatibilityMode(),
        baseJarCommand.getAbiGenerationMode(),
        baseJarCommand.getIsRequiredForSourceOnlyAbi(),
        baseJarCommand.getTrackClassUsage(),
        baseJarCommand.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParam,
        NoOpBuildableContext.INSTANCE,
        buildTargetValue,
        CompilerOutputPathsValueSerializer.deserialize(baseJarCommand.getOutputPathsValue()),
        pathToClassHashes,
        RelPathSerializer.toSortedSetOfRelPath(baseJarCommand.getCompileTimeClasspathPathsList()),
        RelPathSerializer.toSortedSetOfRelPath(baseJarCommand.getJavaSrcsList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(baseJarCommand.getFullJarInfosList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(baseJarCommand.getAbiJarInfosList()),
        RelPathSerializer.toResourceMap(baseJarCommand.getResourcesMapList()),
        cellToPathMappings,
        baseJarCommand.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(baseJarCommand.getLibraryJarParameters())
            : null,
        AbsPathSerializer.deserialize(baseJarCommand.getBuildCellRootPath()),
        libraryJarBaseCommand.hasPathToClasses()
            ? Optional.of(RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClasses()))
            : Optional.empty(),
        ResolvedJavacSerializer.deserialize(baseJarCommand.getResolvedJavac()),
        KotlinExtraParamsSerializer.deserialize(
            baseJarCommand.getResolvedJavacOptions(), libraryJarCommand.getKotlinExtraParams()));

    if (libraryJarBaseCommand.hasUnusedDependenciesParams()) {
      libraryStepsBuilder.addUnusedDependencyStep(
          libraryJarBaseCommand.getUnusedDependenciesParams(),
          cellToPathMappings,
          buildTargetValue.getFullyQualifiedName());
    }

    libraryStepsBuilder.addMakeMissingOutputsStep(
        RelPathSerializer.deserialize(libraryJarBaseCommand.getRootOutput()),
        pathToClassHashes,
        RelPathSerializer.deserialize(libraryJarBaseCommand.getAnnotationsPath()));

    return libraryStepsBuilder.buildIsolatedSteps();
  }

  private ImmutableList<IsolatedStep> handleAbiJarCommand(
      DefaultCompileStepsBuilderFactory<KotlinExtraParams> stepsBuilderFactory,
      AbiJarCommand abiJarCommand,
      boolean withDownwardApi) {
    AbiStepsBuilder abiStepsBuilder = stepsBuilderFactory.getAbiBuilder();

    BaseJarCommand baseJarCommand = abiJarCommand.getBaseJarCommand();
    FilesystemParams filesystemParams = baseJarCommand.getFilesystemParams();

    abiStepsBuilder.addBuildStepsForAbi(
        baseJarCommand.getAbiCompatibilityMode(),
        baseJarCommand.getAbiGenerationMode(),
        baseJarCommand.getIsRequiredForSourceOnlyAbi(),
        baseJarCommand.getTrackClassUsage(),
        baseJarCommand.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParams,
        NoOpBuildableContext.INSTANCE,
        BuildTargetValueSerializer.deserialize(baseJarCommand.getBuildTargetValue()),
        CompilerOutputPathsValueSerializer.deserialize(baseJarCommand.getOutputPathsValue()),
        RelPathSerializer.toSortedSetOfRelPath(baseJarCommand.getCompileTimeClasspathPathsList()),
        RelPathSerializer.toSortedSetOfRelPath(baseJarCommand.getJavaSrcsList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(baseJarCommand.getFullJarInfosList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(baseJarCommand.getAbiJarInfosList()),
        RelPathSerializer.toResourceMap(baseJarCommand.getResourcesMapList()),
        RelPathSerializer.toCellToPathMapping(baseJarCommand.getCellToPathMappingsMap()),
        abiJarCommand.hasAbiJarParameters()
            ? JarParametersSerializer.deserialize(abiJarCommand.getAbiJarParameters())
            : null,
        baseJarCommand.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(baseJarCommand.getLibraryJarParameters())
            : null,
        AbsPathSerializer.deserialize(baseJarCommand.getBuildCellRootPath()),
        ResolvedJavacSerializer.deserialize(baseJarCommand.getResolvedJavac()),
        KotlinExtraParamsSerializer.deserialize(
            baseJarCommand.getResolvedJavacOptions(), abiJarCommand.getKotlinExtraParams()));

    return abiStepsBuilder.buildIsolatedSteps();
  }
}
