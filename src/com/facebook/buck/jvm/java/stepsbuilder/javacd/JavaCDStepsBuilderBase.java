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
import com.facebook.buck.javacd.model.ResolvedJavacOptions;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.DefaultBaseJavaAbiInfo;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.JavaExtraParams;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.AbsPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JarParametersSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.JavaAbiInfoSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacOptionsSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.ResolvedJavacSerializer;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.protobuf.Message;
import javax.annotation.Nullable;

/** Creates a worker tool step that would communicate with JavaCD process. */
abstract class JavaCDStepsBuilderBase<T extends Message> implements JavaCompileStepsBuilder {

  /** Type of the command that javacd should execute. */
  protected enum Type {
    LIBRARY_JAR,
    ABI_JAR,
    LIBRARY_JAR_PIPELINE,
    ABI_JAR_PIPELINE,
  }

  private final BuildJavaCommand.Builder commandBuilder = BuildJavaCommand.newBuilder();
  protected final Type type;
  private final JavaCDParams javaCDParams;

  protected JavaCDStepsBuilderBase(
      boolean hasAnnotationProcessing,
      BuildJavaCommand.SpoolMode spoolMode,
      boolean withDownwardApi,
      Type type,
      JavaCDParams javaCDParams) {
    this.type = type;
    this.javaCDParams = javaCDParams;
    commandBuilder.setHasAnnotationProcessing(hasAnnotationProcessing);
    commandBuilder.setWithDownwardApi(withDownwardApi);
    commandBuilder.setSpoolMode(spoolMode);
  }

  @Override
  public ImmutableList<IsolatedStep> buildIsolatedSteps() {
    switch (type) {
      case LIBRARY_JAR:
        commandBuilder.setLibraryJarCommand((LibraryJarCommand) buildCommand());
        break;

      case ABI_JAR:
        commandBuilder.setAbiJarCommand((AbiJarCommand) buildCommand());
        break;

      case ABI_JAR_PIPELINE:
      case LIBRARY_JAR_PIPELINE:
      default:
        throw new IllegalStateException(type + " is not supported!");
    }

    BuildJavaCommand buildJavaCommand = commandBuilder.build();
    if (javaCDParams.hasJavaCDEnabled()) {
      return ImmutableList.of(new JavaCDWorkerToolStep(buildJavaCommand, javaCDParams));
    }
    return new JavaCDWorkerToolStepsBuilder(buildJavaCommand).getSteps();
  }

  protected abstract T buildCommand();

  protected BaseJarCommand buildBaseJarCommand(
      BaseJarCommand.AbiGenerationMode abiCompatibilityMode,
      BaseJarCommand.AbiGenerationMode abiGenerationMode,
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
      builder.addFullJarInfos(toJavaAbiInfo(javaAbiInfo));
    }
    for (BaseJavaAbiInfo javaAbiInfo : abiJarInfos) {
      builder.addAbiJarInfos(toJavaAbiInfo(javaAbiInfo));
    }
    resourcesMap.forEach(
        (key, value) ->
            builder.addResourcesMap(
                BaseJarCommand.RelPathMapEntry.newBuilder()
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

  private JavaAbiInfo toJavaAbiInfo(BaseJavaAbiInfo javaAbiInfo) {
    Preconditions.checkState(javaAbiInfo instanceof DefaultBaseJavaAbiInfo);
    return JavaAbiInfoSerializer.serialize((DefaultBaseJavaAbiInfo) javaAbiInfo);
  }
}
