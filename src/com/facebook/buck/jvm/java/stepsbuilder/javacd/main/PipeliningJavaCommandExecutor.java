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

package com.facebook.buck.jvm.java.stepsbuilder.javacd.main;

import com.facebook.buck.cd.model.java.BasePipeliningCommand;
import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.cd.model.java.LibraryJarBaseCommand;
import com.facebook.buck.cd.model.java.LibraryPipeliningCommand;
import com.facebook.buck.cd.model.java.PipelineState;
import com.facebook.buck.cd.model.java.PipeliningCommand;
import com.facebook.buck.cd.model.java.UnusedDependenciesParams;
import com.facebook.buck.core.build.buildable.context.NoOpBuildableContext;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.downward.model.ResultEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.jvm.cd.JavaLibraryRules;
import com.facebook.buck.jvm.cd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.cd.serialization.java.BuildTargetValueSerializer;
import com.facebook.buck.jvm.cd.serialization.java.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.cd.serialization.java.JarParametersSerializer;
import com.facebook.buck.jvm.cd.serialization.java.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.cd.serialization.java.ResolvedJavacSerializer;
import com.facebook.buck.jvm.cd.workertool.StepExecutionUtils;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.FilesystemParamsUtils;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.DaemonJavacToJarStepFactory;
import com.facebook.buck.jvm.java.JavaStepsBuilder;
import com.facebook.buck.jvm.java.JavacPipelineState;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStepsRunner;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Executes pipelining java compilation command. */
class PipeliningJavaCommandExecutor {

  private PipeliningJavaCommandExecutor() {}

  static void executePipeliningJavaCommand(
      ImmutableList<ActionId> actionIds,
      PipeliningCommand pipeliningCommand,
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      IsolatedEventBus eventBus,
      Platform platform,
      ProcessExecutor processExecutor,
      Console console,
      Clock clock,
      ClassLoaderCache classLoaderCache,
      Optional<SettableFuture<ActionId>> startNextCommandOptional)
      throws IOException {

    DaemonJavacToJarStepFactory javacToJarStepFactory =
        JavaStepsBuilder.getDaemonJavacToJarStepFactory(pipeliningCommand.getBaseCommandParams());

    PipelineState pipelineState = pipeliningCommand.getPipeliningState();
    try (JavacPipelineState javacPipelineState = getJavacPipelineState(pipelineState)) {
      boolean hasAbiCommand = pipeliningCommand.hasAbiCommand();
      boolean hasLibraryCommand = pipeliningCommand.hasLibraryCommand();
      Iterator<ActionId> actionIdIterator = actionIds.iterator();
      IsolatedExecutionContext executionContext = null;

      try {
        if (hasAbiCommand) {
          BasePipeliningCommand abiCommand = pipeliningCommand.getAbiCommand();
          Pair<ImmutableList<IsolatedStep>, AbsPath> abiStepsPair =
              getAbiSteps(abiCommand, javacToJarStepFactory, javacPipelineState, hasLibraryCommand);
          ImmutableList<IsolatedStep> abiSteps = abiStepsPair.getFirst();
          AbsPath ruleCellRoot = abiStepsPair.getSecond();
          ActionId abiActionId = actionIdIterator.next();

          executionContext =
              StepExecutionUtils.createExecutionContext(
                  classLoaderCache,
                  eventBus,
                  platform,
                  processExecutor,
                  console,
                  clock,
                  abiActionId,
                  ruleCellRoot);
          StepExecutionUtils.sendResultEvent(
              StepExecutionUtils.executeSteps(abiSteps, executionContext),
              abiActionId,
              downwardProtocol,
              eventsOutputStream);
        }

        if (hasLibraryCommand) {
          LibraryPipeliningCommand libraryCommand = pipeliningCommand.getLibraryCommand();

          Pair<ImmutableList<IsolatedStep>, AbsPath> libraryStepsPair =
              getLibrarySteps(
                  libraryCommand, javacToJarStepFactory, javacPipelineState, !hasAbiCommand);
          ImmutableList<IsolatedStep> librarySteps = libraryStepsPair.getFirst();
          AbsPath ruleCellRoot = libraryStepsPair.getSecond();

          Preconditions.checkState(actionIdIterator.hasNext());
          ActionId libraryActionId = actionIdIterator.next();
          if (executionContext != null) {
            // we're running a pipeline, so wait for next command signal.
            boolean ok =
                waitForNextCommandSignal(
                    eventsOutputStream,
                    downwardProtocol,
                    startNextCommandOptional,
                    libraryActionId);
            if (ok) {
              StepExecutionUtils.sendResultEvent(
                  IsolatedStepsRunner.executeWithDefaultExceptionHandling(
                      librarySteps, executionContext),
                  libraryActionId,
                  downwardProtocol,
                  eventsOutputStream);
            }
          } else {
            executionContext =
                StepExecutionUtils.createExecutionContext(
                    classLoaderCache,
                    eventBus,
                    platform,
                    processExecutor,
                    console,
                    clock,
                    libraryActionId,
                    ruleCellRoot);
            StepExecutionUtils.sendResultEvent(
                IsolatedStepsRunner.executeWithDefaultExceptionHandling(
                    librarySteps, executionContext),
                libraryActionId,
                downwardProtocol,
                eventsOutputStream);
          }
        }
      } finally {
        if (executionContext != null) {
          executionContext.close();
        }
      }
    }
    StepExecutionUtils.writePipelineFinishedEvent(downwardProtocol, eventsOutputStream, actionIds);
  }

  private static boolean waitForNextCommandSignal(
      OutputStream eventsOutputStream,
      DownwardProtocol downwardProtocol,
      Optional<SettableFuture<ActionId>> startNextCommandOptional,
      ActionId libraryActionId)
      throws IOException {
    Preconditions.checkState(
        startNextCommandOptional.isPresent(),
        "`startNextCommandOptional` has to be present if pipelining command contains more than one command.");
    Future<ActionId> waitForTheNextCommandFuture = startNextCommandOptional.get();

    Optional<String> errorMessageOptional =
        waitForFuture(libraryActionId, waitForTheNextCommandFuture);
    if (errorMessageOptional.isEmpty()) {
      return true;
    }

    String errorMessage = errorMessageOptional.get();
    ResultEvent resultEvent =
        ResultEvent.newBuilder()
            .setActionId(libraryActionId.getValue())
            .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
            .setMessage(errorMessage)
            .build();
    StepExecutionUtils.writeResultEvent(downwardProtocol, eventsOutputStream, resultEvent);
    return false;
  }

  private static Optional<String> waitForFuture(
      ActionId libraryActionId, Future<ActionId> waitForTheNextCommandFuture) {
    String errorMessage = null;
    try {
      ActionId actionId = waitForTheNextCommandFuture.get();
      if (!actionId.equals(libraryActionId)) {
        errorMessage =
            "Received action id: " + actionId + " not equals to excepted one: " + libraryActionId;
      }
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
      DaemonJavacToJarStepFactory daemonJavacToJarStepFactory,
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
        daemonJavacToJarStepFactory,
        resourcesMap,
        stepsBuilder,
        javacPipelineState,
        compilerOutputPathsValue.getSourceAbiCompilerOutputPath());

    if (createLibraryOutputDirectories) {
      appendWithCreateDirectorySteps(
          daemonJavacToJarStepFactory,
          resourcesMap,
          stepsBuilder,
          javacPipelineState,
          compilerOutputPathsValue.getLibraryCompilerOutputPath());
    }

    daemonJavacToJarStepFactory.createPipelinedCompileToJarStep(
        filesystemParams,
        RelPathSerializer.toCellToPathMapping(abiCommand.getCellToPathMappingsMap()),
        buildTargetValue,
        javacPipelineState,
        !javacPipelineState.isRunning(),
        compilerOutputPathsValue,
        stepsBuilder,
        NoOpBuildableContext.INSTANCE,
        resourcesMap);

    AbsPath ruleCellRoot = JavaStepsBuilder.getRootPath(filesystemParams);
    ImmutableList<IsolatedStep> abiSteps = stepsBuilder.build();
    return new Pair<>(abiSteps, ruleCellRoot);
  }

  private static Pair<ImmutableList<IsolatedStep>, AbsPath> getLibrarySteps(
      LibraryPipeliningCommand libraryCommand,
      DaemonJavacToJarStepFactory daemonJavacToJarStepFactory,
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
          daemonJavacToJarStepFactory,
          resourcesMap,
          stepsBuilder,
          javacPipelineState,
          compilerOutputPathsValue.getLibraryCompilerOutputPath());
    }

    daemonJavacToJarStepFactory.createPipelinedCompileToJarStep(
        filesystemParams,
        cellToPathMappings,
        buildTargetValue,
        javacPipelineState,
        !javacPipelineState.isRunning(),
        compilerOutputPathsValue,
        stepsBuilder,
        NoOpBuildableContext.INSTANCE,
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
      DaemonJavacToJarStepFactory daemonJavacToJarStepFactory,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableList.Builder<IsolatedStep> stepsBuilder,
      JavacPipelineState javacPipelineState,
      CompilerOutputPaths compilerOutputPaths) {
    CompilerParameters compilerParameters = javacPipelineState.getCompilerParameters();
    boolean emptySources = compilerParameters.getSourceFilePaths().isEmpty();

    stepsBuilder.addAll(
        daemonJavacToJarStepFactory.getCompilerSetupIsolatedSteps(
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
