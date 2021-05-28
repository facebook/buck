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

package com.facebook.buck.jvm.java.stepsbuilder.javacd;

import com.facebook.buck.core.build.buildable.context.NoOpBuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.AbiJarCommand;
import com.facebook.buck.javacd.model.BaseCommandParams;
import com.facebook.buck.javacd.model.BaseJarCommand;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.LibraryJarBaseCommand;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.JavaExtraParams;
import com.facebook.buck.jvm.java.stepsbuilder.AbiStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.impl.DefaultJavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.AbsPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JarParametersSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JavaAbiInfoSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacSerializer;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** Java steps builder */
public class JavaStepsBuilder {

  private final ImmutableList<IsolatedStep> steps;
  private final AbsPath ruleCellRoot;

  public JavaStepsBuilder(BuildJavaCommand buildJavaCommand) {
    Pair<AbsPath, ImmutableList<IsolatedStep>> pair = buildSteps(buildJavaCommand);
    this.ruleCellRoot = pair.getFirst();
    this.steps = pair.getSecond();
  }

  /** Returns {@link IsolatedStep}s from the passed protobuf message */
  public ImmutableList<IsolatedStep> getSteps() {
    return steps;
  }

  /** Returns rule cell root. */
  public AbsPath getRuleCellRoot() {
    return ruleCellRoot;
  }

  private Pair<AbsPath, ImmutableList<IsolatedStep>> buildSteps(BuildJavaCommand buildJavaCommand) {
    DefaultJavaCompileStepsBuilderFactory<JavaExtraParams> factory =
        creteDefaultStepsFactory(buildJavaCommand);
    boolean withDownwardApi = buildJavaCommand.getBaseCommandParams().getWithDownwardApi();

    BuildJavaCommand.CommandCase commandCase = buildJavaCommand.getCommandCase();
    AbsPath ruleCellRoot;

    JavaCompileStepsBuilder javaCompileStepsBuilder;
    switch (commandCase) {
      case LIBRARYJARCOMMAND:
        LibraryJarCommand libraryJarCommand = buildJavaCommand.getLibraryJarCommand();
        LibraryStepsBuilder libraryJarBuilder = factory.getLibraryBuilder();
        ruleCellRoot =
            handleLibraryJarCommand(libraryJarBuilder, libraryJarCommand, withDownwardApi);
        javaCompileStepsBuilder = libraryJarBuilder;
        break;

      case ABIJARCOMMAND:
        AbiJarCommand abiJarCommand = buildJavaCommand.getAbiJarCommand();
        AbiStepsBuilder abiJarBuilder = factory.getAbiBuilder();
        ruleCellRoot = handleAbiJarCommand(abiJarBuilder, abiJarCommand, withDownwardApi);

        javaCompileStepsBuilder = abiJarBuilder;
        break;

      case COMMAND_NOT_SET:
      default:
        throw new IllegalStateException(commandCase + " is not supported!");
    }

    return new Pair<>(ruleCellRoot, javaCompileStepsBuilder.buildIsolatedSteps());
  }

  private AbsPath handleLibraryJarCommand(
      LibraryStepsBuilder libraryJarBuilder,
      LibraryJarCommand libraryJarCommand,
      boolean withDownwardApi) {
    BaseJarCommand command = libraryJarCommand.getBaseJarCommand();
    LibraryJarBaseCommand libraryJarBaseCommand = libraryJarCommand.getLibraryJarBaseCommand();

    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        RelPathSerializer.toCellToPathMapping(command.getCellToPathMappingsMap());
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(command.getBuildTargetValue());
    RelPath pathToClassHashes =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes());

    FilesystemParams filesystemParams = command.getFilesystemParams();

    libraryJarBuilder.addBuildStepsForLibrary(
        command.getAbiCompatibilityMode(),
        command.getAbiGenerationMode(),
        command.getIsRequiredForSourceOnlyAbi(),
        ImmutableList.copyOf(libraryJarCommand.getPostprocessClassesCommandsList()),
        command.getTrackClassUsage(),
        command.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParams,
        NoOpBuildableContext.INSTANCE,
        buildTargetValue,
        CompilerOutputPathsValueSerializer.deserialize(command.getOutputPathsValue()),
        pathToClassHashes,
        RelPathSerializer.toSortedSetOfRelPath(command.getCompileTimeClasspathPathsList()),
        RelPathSerializer.toSortedSetOfRelPath(command.getJavaSrcsList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(command.getFullJarInfosList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(command.getAbiJarInfosList()),
        RelPathSerializer.toResourceMap(command.getResourcesMapList()),
        cellToPathMappings,
        command.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(command.getLibraryJarParameters())
            : null,
        AbsPathSerializer.deserialize(command.getBuildCellRootPath()),
        libraryJarBaseCommand.hasPathToClasses()
            ? Optional.of(RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClasses()))
            : Optional.empty(),
        ResolvedJavacSerializer.deserialize(command.getResolvedJavac()),
        JavaExtraParams.of(
            ResolvedJavacOptionsSerializer.deserialize(command.getResolvedJavacOptions())));

    maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
        libraryJarCommand,
        cellToPathMappings,
        buildTargetValue,
        pathToClassHashes,
        libraryJarBuilder);

    return getRootPath(filesystemParams);
  }

  private AbsPath handleAbiJarCommand(
      AbiStepsBuilder abiJarBuilder, AbiJarCommand abiJarCommand, boolean withDownwardApi) {
    BaseJarCommand command = abiJarCommand.getBaseJarCommand();

    FilesystemParams filesystemParams = command.getFilesystemParams();

    abiJarBuilder.addBuildStepsForAbi(
        command.getAbiCompatibilityMode(),
        command.getAbiGenerationMode(),
        command.getIsRequiredForSourceOnlyAbi(),
        command.getTrackClassUsage(),
        command.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParams,
        NoOpBuildableContext.INSTANCE,
        BuildTargetValueSerializer.deserialize(command.getBuildTargetValue()),
        CompilerOutputPathsValueSerializer.deserialize(command.getOutputPathsValue()),
        RelPathSerializer.toSortedSetOfRelPath(command.getCompileTimeClasspathPathsList()),
        RelPathSerializer.toSortedSetOfRelPath(command.getJavaSrcsList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(command.getFullJarInfosList()),
        JavaAbiInfoSerializer.toJavaAbiInfo(command.getAbiJarInfosList()),
        RelPathSerializer.toResourceMap(command.getResourcesMapList()),
        RelPathSerializer.toCellToPathMapping(command.getCellToPathMappingsMap()),
        abiJarCommand.hasAbiJarParameters()
            ? JarParametersSerializer.deserialize(abiJarCommand.getAbiJarParameters())
            : null,
        command.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(command.getLibraryJarParameters())
            : null,
        AbsPathSerializer.deserialize(command.getBuildCellRootPath()),
        ResolvedJavacSerializer.deserialize(command.getResolvedJavac()),
        JavaExtraParams.of(
            ResolvedJavacOptionsSerializer.deserialize(command.getResolvedJavacOptions())));

    return getRootPath(filesystemParams);
  }

  public static AbsPath getRootPath(FilesystemParams filesystemParams) {
    return AbsPath.get(filesystemParams.getRootPath().getPath());
  }

  private static DefaultJavaCompileStepsBuilderFactory<JavaExtraParams> creteDefaultStepsFactory(
      BuildJavaCommand buildJavaCommand) {
    BaseJavacToJarStepFactory baseJavacToJarStepFactory =
        getBaseJavacToJarStepFactory(buildJavaCommand.getBaseCommandParams());
    return new DefaultJavaCompileStepsBuilderFactory<>(baseJavacToJarStepFactory);
  }

  /** Returns {@link BaseJavacToJarStepFactory} */
  public static BaseJavacToJarStepFactory getBaseJavacToJarStepFactory(
      BaseCommandParams baseCommandParams) {
    return new BaseJavacToJarStepFactory(
        baseCommandParams.getSpoolMode(),
        baseCommandParams.getHasAnnotationProcessing(),
        baseCommandParams.getWithDownwardApi());
  }

  private void maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
      LibraryJarCommand command,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue buildTargetValue,
      RelPath pathToClassHashes,
      LibraryStepsBuilder javaCompileStepsBuilder) {
    LibraryJarBaseCommand libraryJarBaseCommand = command.getLibraryJarBaseCommand();
    if (libraryJarBaseCommand.hasUnusedDependenciesParams()) {
      UnusedDependenciesParams unusedDependenciesParams =
          libraryJarBaseCommand.getUnusedDependenciesParams();
      javaCompileStepsBuilder.addUnusedDependencyStep(
          unusedDependenciesParams, cellToPathMappings, buildTargetValue.getFullyQualifiedName());
    }

    javaCompileStepsBuilder.addMakeMissingOutputsStep(
        RelPathSerializer.deserialize(libraryJarBaseCommand.getRootOutput()),
        pathToClassHashes,
        RelPathSerializer.deserialize(libraryJarBaseCommand.getAnnotationsPath()));
  }
}
