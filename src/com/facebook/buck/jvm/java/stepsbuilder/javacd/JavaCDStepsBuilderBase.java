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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rules.common.RecordArtifactVerifier;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.javacd.model.AbiJarCommand;
import com.facebook.buck.javacd.model.BaseCommandParams;
import com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode;
import com.facebook.buck.javacd.model.BaseJarCommand;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.javacd.model.RelPathMapEntry;
import com.facebook.buck.javacd.model.ResolvedJavacOptions;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.JavaCDEvent;
import com.facebook.buck.jvm.java.JavaCDRolloutMode;
import com.facebook.buck.jvm.java.JavaExtraParams;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.AbsPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JarParametersSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JavaAbiInfoSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDRolloutModeValue;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.protobuf.Message;
import java.util.Optional;
import javax.annotation.Nullable;

/** Creates a worker tool step that would communicate with JavaCD process. */
abstract class JavaCDStepsBuilderBase<T extends Message> implements JavaCompileStepsBuilder {

  private static final Logger LOG = Logger.get(JavaCDStepsBuilderBase.class);

  /** Type of the command that javacd should execute. */
  protected enum Type {
    LIBRARY_JAR,
    ABI_JAR,
  }

  private final BuildJavaCommand.Builder commandBuilder = BuildJavaCommand.newBuilder();
  protected final Type type;
  private final JavaCDParams javaCDParams;

  protected JavaCDStepsBuilderBase(
      boolean hasAnnotationProcessing,
      SpoolMode spoolMode,
      boolean withDownwardApi,
      Type type,
      JavaCDParams javaCDParams) {
    this.type = type;
    this.javaCDParams = javaCDParams;
    BaseCommandParams.Builder baseCommandParamsBuilder =
        commandBuilder.getBaseCommandParamsBuilder();
    baseCommandParamsBuilder.setHasAnnotationProcessing(hasAnnotationProcessing);
    baseCommandParamsBuilder.setWithDownwardApi(withDownwardApi);
    baseCommandParamsBuilder.setSpoolMode(spoolMode);
  }

  @Override
  public ImmutableList<IsolatedStep> buildIsolatedSteps(
      Optional<BuckEventBus> buckEventBusOptional) {
    switch (type) {
      case LIBRARY_JAR:
        commandBuilder.setLibraryJarCommand((LibraryJarCommand) buildCommand());
        break;

      case ABI_JAR:
        commandBuilder.setAbiJarCommand((AbiJarCommand) buildCommand());
        break;

      default:
        throw new IllegalStateException(type + " is not supported!");
    }

    BuildJavaCommand buildJavaCommand = commandBuilder.build();
    if (hasJavaCDEnabled(getEventBus(buckEventBusOptional))) {
      return ImmutableList.of(new JavaCDWorkerToolStep(buildJavaCommand, javaCDParams));
    }
    return new JavaStepsBuilder(buildJavaCommand).getSteps();
  }

  private BuckEventBus getEventBus(Optional<BuckEventBus> buckEventBusOptional) {
    return buckEventBusOptional.orElseThrow(
        () ->
            new IllegalStateException(
                "buck event bus has to be passed if steps are creating in the buck process"));
  }

  private boolean hasJavaCDEnabled(BuckEventBus eventBus) {
    JavaCDRolloutModeValue javaCDRolloutModeValue = javaCDParams.getJavaCDRolloutModeValue();
    JavaCDRolloutMode javaCDRolloutMode = javaCDRolloutModeValue.getJavacdMode();
    boolean hasJavaCDEnabled = getJavaCDEnabledValue(javaCDRolloutMode);

    // Emit events only for the very first invocation
    if (javaCDRolloutModeValue.isFirstInvocation().compareAndSet(true, false)) {
      if (javaCDRolloutMode != JavaCDRolloutMode.UNKNOWN) {
        eventBus.post(new ExperimentEvent("javacd_mode", javaCDRolloutMode.toString()));
      }
      eventBus.post(new JavaCDEvent(hasJavaCDEnabled));
    }

    return hasJavaCDEnabled;
  }

  private boolean getJavaCDEnabledValue(JavaCDRolloutMode javaCDRolloutMode) {
    if (javaCDRolloutMode != JavaCDRolloutMode.UNKNOWN) {
      return javaCDRolloutMode == JavaCDRolloutMode.ENABLED;
    }

    LOG.info("JavaCD mode is not set. Using javacd_enabled property");
    return javaCDParams.hasJavaCDEnabled();
  }

  protected abstract T buildCommand();

  protected BaseJarCommand buildBaseJarCommand(
      AbiGenerationMode abiCompatibilityMode,
      AbiGenerationMode abiGenerationMode,
      boolean isRequiredForSourceOnlyAbi,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      FilesystemParams filesystemParams,
      BuildTargetValue buildTargetValue,
      CompilerOutputPathsValue compilerOutputPathsValue,
      ImmutableSortedSet<RelPath> compileTimeClasspathPaths,
      ImmutableSortedSet<RelPath> javaSrcs,
      ImmutableList<BaseJavaAbiInfo> fullJarInfos,
      ImmutableList<BaseJavaAbiInfo> abiJarInfos,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      @Nullable JarParameters libraryJarParameters,
      AbsPath buildCellRootPath,
      ResolvedJavac resolvedJavac,
      CompileToJarStepFactory.ExtraParams extraParams) {
    BaseJarCommand.Builder builder = BaseJarCommand.newBuilder();

    builder.setAbiCompatibilityMode(abiCompatibilityMode);
    builder.setAbiGenerationMode(abiGenerationMode);
    builder.setIsRequiredForSourceOnlyAbi(isRequiredForSourceOnlyAbi);
    builder.setTrackClassUsage(trackClassUsage);
    builder.setTrackJavacPhaseEvents(trackJavacPhaseEvents);
    builder.setFilesystemParams(filesystemParams);
    builder.setBuildTargetValue(BuildTargetValueSerializer.serialize(buildTargetValue));
    builder.setOutputPathsValue(
        CompilerOutputPathsValueSerializer.serialize(compilerOutputPathsValue));

    for (RelPath compileTimeClasspathPath : compileTimeClasspathPaths) {
      builder.addCompileTimeClasspathPaths(RelPathSerializer.serialize(compileTimeClasspathPath));
    }
    for (RelPath javaSrc : javaSrcs) {
      builder.addJavaSrcs(RelPathSerializer.serialize(javaSrc));
    }
    for (BaseJavaAbiInfo javaAbiInfo : fullJarInfos) {
      builder.addFullJarInfos(JavaAbiInfoSerializer.toJavaAbiInfo(javaAbiInfo));
    }
    for (BaseJavaAbiInfo javaAbiInfo : abiJarInfos) {
      builder.addAbiJarInfos(JavaAbiInfoSerializer.toJavaAbiInfo(javaAbiInfo));
    }
    resourcesMap.forEach(
        (key, value) ->
            builder.addResourcesMap(
                RelPathMapEntry.newBuilder()
                    .setKey(RelPathSerializer.serialize(key))
                    .setValue(RelPathSerializer.serialize(value))
                    .build()));
    cellToPathMappings.forEach(
        (key, value) ->
            builder.putCellToPathMappings(key.getName(), RelPathSerializer.serialize(value)));
    if (libraryJarParameters != null) {
      builder.setLibraryJarParameters(JarParametersSerializer.serialize(libraryJarParameters));
    }
    builder.setBuildCellRootPath(AbsPathSerializer.serialize(buildCellRootPath));
    builder.setResolvedJavac(ResolvedJavacSerializer.serialize(resolvedJavac));
    builder.setResolvedJavacOptions(toResolvedJavacOptions(extraParams));

    return builder.build();
  }

  private ResolvedJavacOptions toResolvedJavacOptions(
      CompileToJarStepFactory.ExtraParams extraParams) {
    Preconditions.checkState(extraParams instanceof JavaExtraParams);
    JavaExtraParams javaExtraParams = (JavaExtraParams) extraParams;
    return ResolvedJavacOptionsSerializer.serialize(javaExtraParams.getResolvedJavacOptions());
  }

  protected void recordArtifacts(
      BuildableContext buildableContext,
      CompilerOutputPathsValue compilerOutputPathsValue,
      BuildTargetValue buildTargetValue,
      ImmutableSortedSet<RelPath> javaSrcs,
      boolean trackClassUsage,
      @Nullable JarParameters jarParameters) {
    Preconditions.checkState(buildableContext instanceof RecordArtifactVerifier);
    RecordArtifactVerifier recordArtifactVerifier = (RecordArtifactVerifier) buildableContext;

    CompilerOutputPaths outputPaths =
        compilerOutputPathsValue.getByType(buildTargetValue.getType());
    boolean needDepFile = !javaSrcs.isEmpty() && trackClassUsage;

    if (needDepFile) {
      RelPath depFilePath =
          CompilerOutputPaths.getJavaDepFilePath(outputPaths.getOutputJarDirPath());
      recordArtifactVerifier.recordArtifact(depFilePath.getPath());
    }
    recordArtifactVerifier.recordArtifact(outputPaths.getAnnotationPath().getPath());
    if (jarParameters != null) {
      recordArtifactVerifier.recordArtifact(jarParameters.getJarPath().getPath());
    }
  }
}
