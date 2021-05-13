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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.LibraryJarBaseCommand;
import com.facebook.buck.javacd.model.LibraryPipeliningCommand;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.PipeliningCommand;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.FilesystemParamsUtils;
import com.facebook.buck.jvm.java.JavacPipelineState;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.JavaStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JarParametersSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacSerializer;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Executes pipelining java compilation command. */
class PipeliningJavaCommandExecutor {

  private PipeliningJavaCommandExecutor() {}

  static void executePipeliningJavaCommand(
      List<String> actionIds,
      PipeliningCommand pipeliningCommand,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock,
      Optional<SettableFuture<Unit>> startNextCommandOptional)
      throws IOException {

    BaseJavacToJarStepFactory javacToJarStepFactory =
        JavaStepsBuilder.getBaseJavacToJarStepFactory(pipeliningCommand.getBaseCommandParams());

    PipelineState pipelineState = pipeliningCommand.getPipeliningState();
    try (JavacPipelineState javacPipelineState = getJavacPipelineState(pipelineState)) {

      // context for reuse in the next command in the pipeline
      Optional<IsolatedExecutionContext> contextOptional = Optional.empty();
      boolean hasAbiCommand = pipeliningCommand.hasAbiCommand();
      boolean hasLibraryCommand = pipeliningCommand.hasLibraryCommand();
      Iterator<String> actionIdIterator = actionIds.iterator();

      if (hasAbiCommand) {
        BasePipeliningCommand abiCommand = pipeliningCommand.getAbiCommand();
        Pair<ImmutableList<IsolatedStep>, AbsPath> abiStepsPair =
            getAbiSteps(abiCommand, javacToJarStepFactory, javacPipelineState, hasLibraryCommand);
        ImmutableList<IsolatedStep> abiSteps = abiStepsPair.getFirst();
        AbsPath ruleCellRoot = abiStepsPair.getSecond();
        String abiActionId = actionIdIterator.next();

        boolean closeExecutionContext = !hasLibraryCommand;
        contextOptional =
            StepExecutionUtils.executeSteps(
                eventBus,
                eventsOutputStream,
                downwardProtocol,
                platform,
                processExecutor,
                console,
                clock,
                abiActionId,
                ruleCellRoot,
                abiSteps,
                closeExecutionContext);
      }

      if (hasLibraryCommand) {
        LibraryPipeliningCommand libraryCommand = pipeliningCommand.getLibraryCommand();

        Pair<ImmutableList<IsolatedStep>, AbsPath> libraryStepsPair =
            getLibrarySteps(
                libraryCommand, javacToJarStepFactory, javacPipelineState, !hasAbiCommand);
        ImmutableList<IsolatedStep> librarySteps = libraryStepsPair.getFirst();
        AbsPath ruleCellRoot = libraryStepsPair.getSecond();

        Preconditions.checkState(actionIdIterator.hasNext());
        String libraryActionId = actionIdIterator.next();

        if (contextOptional.isPresent()) {
          try (IsolatedExecutionContext isolatedExecutionContext = contextOptional.get()) {
            boolean ok =
                waitForNextCommandSignal(
                    eventsOutputStream,
                    downwardProtocol,
                    startNextCommandOptional,
                    libraryActionId);
            if (ok) {
              StepExecutionUtils.executeSteps(
                  isolatedExecutionContext,
                  eventsOutputStream,
                  downwardProtocol,
                  libraryActionId,
                  librarySteps);
            }
          }
        } else {
          StepExecutionUtils.executeSteps(
              eventBus,
              eventsOutputStream,
              downwardProtocol,
              platform,
              processExecutor,
              console,
              clock,
              libraryActionId,
              ruleCellRoot,
              librarySteps);
        }
      }
    }
    StepExecutionUtils.writePipelineFinishedEvent(downwardProtocol, eventsOutputStream);
  }

  private static boolean waitForNextCommandSignal(
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      Optional<SettableFuture<Unit>> startNextCommandOptional,
      String libraryActionId)
      throws IOException {
    Preconditions.checkState(
        startNextCommandOptional.isPresent(),
        "`startNextCommandOptional` has to be present if pipelining command contains more than one command.");
    Future<?> waitForTheNextCommandFuture = startNextCommandOptional.get();

    Optional<String> errorMessageOptional =
        waitForFuture(libraryActionId, waitForTheNextCommandFuture);
    if (!errorMessageOptional.isPresent()) {
      return true;
    }

    String errorMessage = errorMessageOptional.get();
    ResultEvent resultEvent =
        ResultEvent.newBuilder()
            .setActionId(libraryActionId)
            .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
            .setMessage(errorMessage)
            .build();
    StepExecutionUtils.writeResultEvent(downwardProtocol, eventsOutputStream, resultEvent);
    return false;
  }

  private static Optional<String> waitForFuture(
      String libraryActionId, Future<?> waitForTheNextCommandFuture) {
    String errorMessage = null;
    try {
      waitForTheNextCommandFuture.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      errorMessage =
          String.format(
              "Thread that waited for a signal to start the next pipelining command %s  has been interrupted",
              libraryActionId);
    } catch (ExecutionException e) {
      errorMessage =
          String.format(
              "Exception %s%n while waiting for start next pipelining command signal for action id: %s",
              getErrorMessage(e), libraryActionId);
    }

    return Optional.ofNullable(errorMessage);
  }

  private static String getErrorMessage(ExecutionException e) {
    Exception cause = (Exception) e.getCause();
    if (cause instanceof HumanReadableException) {
      return ((HumanReadableException) cause).getHumanReadableErrorMessage();
    }
    return Throwables.getStackTraceAsString(cause);
  }

  private static Pair<ImmutableList<IsolatedStep>, AbsPath> getAbiSteps(
      BasePipeliningCommand abiCommand,
      BaseJavacToJarStepFactory baseJavacToJarStepFactory,
      JavacPipelineState javacPipelineState,
      boolean createLibraryOutputDirectories) {

    FilesystemParams filesystemParams = abiCommand.getFilesystemParams();
    CompilerOutputPathsValue compilerOutputPathsValue =
        CompilerOutputPathsValueSerializer.deserialize(abiCommand.getOutputPathsValue());
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(abiCommand.getBuildTargetValue());
    ImmutableMap<RelPath, RelPath> resourcesMap =
        RelPathSerializer.toResourceMap(abiCommand.getResourcesMapList());

    ImmutableList.Builder<IsolatedStep> stepsBuilder = ImmutableList.builder();

    appendWithCreateDirectorySteps(
        baseJavacToJarStepFactory,
        resourcesMap,
        stepsBuilder,
        javacPipelineState,
        compilerOutputPathsValue.getSourceAbiCompilerOutputPath());

    if (createLibraryOutputDirectories) {
      appendWithCreateDirectorySteps(
          baseJavacToJarStepFactory,
          resourcesMap,
          stepsBuilder,
          javacPipelineState,
          compilerOutputPathsValue.getLibraryCompilerOutputPath());
    }

    baseJavacToJarStepFactory.createPipelinedCompileToJarStep(
        filesystemParams,
        RelPathSerializer.toCellToPathMapping(abiCommand.getCellToPathMappingsMap()),
        buildTargetValue,
        javacPipelineState,
        !javacPipelineState.isRunning(),
        compilerOutputPathsValue,
        stepsBuilder,
        path -> {},
        resourcesMap);

    AbsPath ruleCellRoot = JavaStepsBuilder.getRootPath(filesystemParams);
    ImmutableList<IsolatedStep> abiSteps = stepsBuilder.build();
    return new Pair<>(abiSteps, ruleCellRoot);
  }

  private static Pair<ImmutableList<IsolatedStep>, AbsPath> getLibrarySteps(
      LibraryPipeliningCommand libraryCommand,
      BaseJavacToJarStepFactory baseJavacToJarStepFactory,
      JavacPipelineState javacPipelineState,
      boolean createLibraryOutputDirectories) {

    BasePipeliningCommand basePipeliningCommand = libraryCommand.getBasePipeliningCommand();
    FilesystemParams filesystemParams = basePipeliningCommand.getFilesystemParams();
    CompilerOutputPathsValue compilerOutputPathsValue =
        CompilerOutputPathsValueSerializer.deserialize(basePipeliningCommand.getOutputPathsValue());
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(basePipeliningCommand.getBuildTargetValue());
    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        RelPathSerializer.toCellToPathMapping(basePipeliningCommand.getCellToPathMappingsMap());
    ImmutableMap<RelPath, RelPath> resourcesMap =
        RelPathSerializer.toResourceMap(basePipeliningCommand.getResourcesMapList());

    ImmutableList.Builder<IsolatedStep> stepsBuilder = ImmutableList.builder();

    if (createLibraryOutputDirectories) {
      appendWithCreateDirectorySteps(
          baseJavacToJarStepFactory,
          resourcesMap,
          stepsBuilder,
          javacPipelineState,
          compilerOutputPathsValue.getLibraryCompilerOutputPath());
    }

    baseJavacToJarStepFactory.createPipelinedCompileToJarStep(
        filesystemParams,
        cellToPathMappings,
        buildTargetValue,
        javacPipelineState,
        !javacPipelineState.isRunning(),
        compilerOutputPathsValue,
        stepsBuilder,
        path -> {},
        resourcesMap);

    LibraryJarBaseCommand libraryJarBaseCommand = libraryCommand.getLibraryJarBaseCommand();
    JavaLibraryRules.addAccumulateClassNamesStep(
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams),
        stepsBuilder,
        libraryJarBaseCommand.hasPathToClasses()
            ? Optional.of(RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClasses()))
            : Optional.empty(),
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes()));

    if (libraryJarBaseCommand.hasUnusedDependenciesParams()) {
      UnusedDependenciesParams unusedDependenciesParams =
          libraryJarBaseCommand.getUnusedDependenciesParams();

      String buildozerPath = unusedDependenciesParams.getBuildozerPath();
      stepsBuilder.add(
          UnusedDependenciesFinder.of(
              buildTargetValue.getFullyQualifiedName(),
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

    RelPath rootOutput = RelPathSerializer.deserialize(libraryJarBaseCommand.getRootOutput());
    RelPath pathToClassHashes =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes());
    RelPath annotationsPath =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getAnnotationsPath());
    stepsBuilder.add(new MakeMissingOutputsStep(rootOutput, pathToClassHashes, annotationsPath));

    AbsPath ruleCellRoot = JavaStepsBuilder.getRootPath(filesystemParams);
    return new Pair<>(stepsBuilder.build(), ruleCellRoot);
  }

  private static void appendWithCreateDirectorySteps(
      BaseJavacToJarStepFactory baseJavacToJarStepFactory,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableList.Builder<IsolatedStep> stepsBuilder,
      JavacPipelineState javacPipelineState,
      CompilerOutputPaths compilerOutputPaths) {
    CompilerParameters compilerParameters = javacPipelineState.getCompilerParameters();
    boolean emptySources = compilerParameters.getSourceFilePaths().isEmpty();

    stepsBuilder.addAll(
        baseJavacToJarStepFactory.getCompilerSetupIsolatedSteps(
            resourcesMap, compilerOutputPaths, emptySources));
    stepsBuilder.addAll(MakeCleanDirectoryIsolatedStep.of(compilerOutputPaths.getAnnotationPath()));
  }

  private static JavacPipelineState getJavacPipelineState(PipelineState pipelineState) {
    return new JavacPipelineState(
        ResolvedJavacSerializer.deserialize(pipelineState.getResolvedJavac()),
        ResolvedJavacOptionsSerializer.deserialize(pipelineState.getResolvedJavacOptions()),
        BuildTargetValueSerializer.deserialize(pipelineState.getBuildTargetValue()),
        JavaLibraryRules.createCompilerParameters(pipelineState),
        pipelineState.hasAbiJarParameters()
            ? JarParametersSerializer.deserialize(pipelineState.getAbiJarParameters())
            : null,
        pipelineState.hasLibraryJarParameters()
            ? JarParametersSerializer.deserialize(pipelineState.getLibraryJarParameters())
            : null,
        pipelineState.getWithDownwardApi());
  }
}
