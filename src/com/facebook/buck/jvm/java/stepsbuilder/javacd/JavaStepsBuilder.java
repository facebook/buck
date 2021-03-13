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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.AbiJarCommand;
import com.facebook.buck.javacd.model.BaseJarCommand;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.JavaAbiInfo;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.JavaExtraParams;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilderBase;
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
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Map;
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
    boolean withDownwardApi = buildJavaCommand.getWithDownwardApi();
    DefaultJavaCompileStepsBuilderFactory<JavaExtraParams> factory =
        creteDefaultStepsFactory(buildJavaCommand, withDownwardApi);

    BuildJavaCommand.CommandCase commandCase = buildJavaCommand.getCommandCase();
    AbsPath ruleCellRoot;

    JavaCompileStepsBuilder javaCompileStepsBuilder;
    switch (commandCase) {
      case LIBRARYJARCOMMAND:
        LibraryJarCommand libraryJarCommand = buildJavaCommand.getLibraryJarCommand();
        LibraryJarStepsBuilder libraryJarBuilder = factory.getLibraryJarBuilder();
        ruleCellRoot =
            handleLibraryJarCommand(libraryJarBuilder, libraryJarCommand, withDownwardApi);
        javaCompileStepsBuilder = libraryJarBuilder;
        break;

      case ABIJARCOMMAND:
        AbiJarCommand abiJarCommand = buildJavaCommand.getAbiJarCommand();
        AbiJarStepsBuilder abiJarBuilder = factory.getAbiJarBuilder();
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
      LibraryJarStepsBuilder libraryJarBuilder,
      LibraryJarCommand libraryJarCommand,
      boolean withDownwardApi) {
    BaseJarCommand command = libraryJarCommand.getBaseJarCommand();

    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        toCellToPathMapping(command.getCellToPathMappingsMap());
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(command.getBuildTargetValue());
    RelPath pathToClassHashes =
        RelPathSerializer.deserialize(libraryJarCommand.getPathToClassHashes());

    FilesystemParams filesystemParams = command.getFilesystemParams();

    libraryJarBuilder.addBuildStepsForLibraryJar(
        command.getAbiCompatibilityMode(),
        command.getAbiGenerationMode(),
        command.getIsRequiredForSourceOnlyAbi(),
        ImmutableList.copyOf(libraryJarCommand.getPostprocessClassesCommandsList()),
        command.getTrackClassUsage(),
        command.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParams,
        path -> {},
        buildTargetValue,
        CompilerOutputPathsValueSerializer.deserialize(command.getOutputPathsValue()),
        pathToClassHashes,
        toSortedSetOfRelPath(command.getCompileTimeClasspathPathsList()),
        toSortedSetOfRelPath(command.getJavaSrcsList()),
        toJavaAbiInfo(command.getFullJarInfosList()),
        toJavaAbiInfo(command.getAbiJarInfosList()),
        toResourceMap(command.getResourcesMapList()),
        cellToPathMappings,
        command.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(command.getLibraryJarParameters())
            : null,
        AbsPathSerializer.deserialize(command.getBuildCellRootPath()),
        libraryJarCommand.hasPathToClasses()
            ? Optional.of(RelPathSerializer.deserialize(libraryJarCommand.getPathToClasses()))
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
      AbiJarStepsBuilder abiJarBuilder, AbiJarCommand abiJarCommand, boolean withDownwardApi) {
    BaseJarCommand command = abiJarCommand.getBaseJarCommand();

    FilesystemParams filesystemParams = command.getFilesystemParams();

    abiJarBuilder.addBuildStepsForAbiJar(
        command.getAbiCompatibilityMode(),
        command.getAbiGenerationMode(),
        command.getIsRequiredForSourceOnlyAbi(),
        command.getTrackClassUsage(),
        command.getTrackJavacPhaseEvents(),
        withDownwardApi,
        filesystemParams,
        path -> {},
        BuildTargetValueSerializer.deserialize(command.getBuildTargetValue()),
        CompilerOutputPathsValueSerializer.deserialize(command.getOutputPathsValue()),
        toSortedSetOfRelPath(command.getCompileTimeClasspathPathsList()),
        toSortedSetOfRelPath(command.getJavaSrcsList()),
        toJavaAbiInfo(command.getFullJarInfosList()),
        toJavaAbiInfo(command.getAbiJarInfosList()),
        toResourceMap(command.getResourcesMapList()),
        toCellToPathMapping(command.getCellToPathMappingsMap()),
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

  private AbsPath getRootPath(FilesystemParams filesystemParams) {
    return AbsPath.get(filesystemParams.getRootPath().getPath());
  }

  private DefaultJavaCompileStepsBuilderFactory<JavaExtraParams> creteDefaultStepsFactory(
      BuildJavaCommand buildJavaCommand, boolean withDownwardApi) {
    BaseJavacToJarStepFactory baseJavacToJarStepFactory =
        new BaseJavacToJarStepFactory(
            buildJavaCommand.getSpoolMode(),
            buildJavaCommand.getHasAnnotationProcessing(),
            withDownwardApi);
    return new DefaultJavaCompileStepsBuilderFactory<>(baseJavacToJarStepFactory);
  }

  private void maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
      LibraryJarCommand command,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue buildTargetValue,
      RelPath pathToClassHashes,
      LibraryStepsBuilderBase javaCompileStepsBuilder) {

    if (command.hasUnusedDependenciesParams()) {
      UnusedDependenciesParams unusedDependenciesParams = command.getUnusedDependenciesParams();
      javaCompileStepsBuilder.addUnusedDependencyStep(
          unusedDependenciesParams, cellToPathMappings, buildTargetValue.getFullyQualifiedName());
    }

    javaCompileStepsBuilder.addMakeMissingOutputsStep(
        RelPathSerializer.deserialize(command.getRootOutput()),
        pathToClassHashes,
        RelPathSerializer.deserialize(command.getAnnotationsPath()));
  }

  private ImmutableMap<RelPath, RelPath> toResourceMap(
      List<BaseJarCommand.RelPathMapEntry> resourcesMapList) {
    ImmutableMap.Builder<RelPath, RelPath> builder =
        ImmutableMap.builderWithExpectedSize(resourcesMapList.size());
    for (BaseJarCommand.RelPathMapEntry entry : resourcesMapList) {
      builder.put(
          RelPathSerializer.deserialize(entry.getKey()),
          RelPathSerializer.deserialize(entry.getValue()));
    }
    return builder.build();
  }

  private ImmutableList<BaseJavaAbiInfo> toJavaAbiInfo(List<JavaAbiInfo> list) {
    ImmutableList.Builder<BaseJavaAbiInfo> builder =
        ImmutableList.builderWithExpectedSize(list.size());
    for (JavaAbiInfo item : list) {
      builder.add(JavaAbiInfoSerializer.deserialize(item));
    }
    return builder.build();
  }

  private ImmutableSortedSet<RelPath> toSortedSetOfRelPath(
      List<com.facebook.buck.javacd.model.RelPath> list) {
    ImmutableSortedSet.Builder<RelPath> builder =
        ImmutableSortedSet.orderedBy(RelPath.comparator());
    for (com.facebook.buck.javacd.model.RelPath item : list) {
      builder.add(RelPathSerializer.deserialize(item));
    }
    return builder.build();
  }

  private ImmutableMap<CanonicalCellName, RelPath> toCellToPathMapping(
      Map<String, com.facebook.buck.javacd.model.RelPath> cellToPathMappings) {
    ImmutableMap.Builder<CanonicalCellName, RelPath> builder =
        ImmutableMap.builderWithExpectedSize(cellToPathMappings.size());
    cellToPathMappings.forEach(
        (key, value) ->
            builder.put(toCanonicalCellName(key), RelPathSerializer.deserialize(value)));
    return builder.build();
  }

  private CanonicalCellName toCanonicalCellName(String cellName) {
    if (cellName.isEmpty()) {
      return CanonicalCellName.rootCell();
    }
    return CanonicalCellName.of(Optional.of(cellName));
  }
}
