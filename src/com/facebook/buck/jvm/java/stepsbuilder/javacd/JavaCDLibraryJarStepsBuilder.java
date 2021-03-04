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
import com.facebook.buck.javacd.model.BaseJarCommand;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;

/** Default implementation of {@link LibraryJarStepsBuilder} */
class JavaCDLibraryJarStepsBuilder extends JavaCDLibraryCompileStepsBuilder<LibraryJarCommand>
    implements LibraryJarStepsBuilder {

  JavaCDLibraryJarStepsBuilder(
      boolean hasAnnotationProcessing,
      BuildJavaCommand.SpoolMode spoolMode,
      boolean withDownwardApi,
      JavaCDParams javaCDParams) {
    super(
        hasAnnotationProcessing,
        spoolMode,
        withDownwardApi,
        Type.LIBRARY_JAR,
        LibraryJarCommand.newBuilder(),
        javaCDParams);
  }

  @Override
  public void addBuildStepsForLibraryJar(
      BaseJarCommand.AbiGenerationMode abiCompatibilityMode,
      BaseJarCommand.AbiGenerationMode abiGenerationMode,
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

    BaseJarCommand baseJarCommand =
        buildBaseJarCommand(
            abiCompatibilityMode,
            abiGenerationMode,
            isRequiredForSourceOnlyAbi,
            trackClassUsage,
            trackJavacPhaseEvents,
            filesystemParams,
            buildTargetValue,
            compilerOutputPathsValue,
            compileTimeClasspathPaths,
            javaSrcs,
            fullJarInfos,
            abiJarInfos,
            resourcesMap,
            cellToPathMappings,
            libraryJarParameters,
            buildCellRootPath,
            resolvedJavac,
            extraParams);

    LibraryJarCommand.Builder libraryJarCommandBuilder = getLibraryJarCommandBuilder();
    libraryJarCommandBuilder.setBaseJarCommand(baseJarCommand);
    for (String postprocessClassesCommand : postprocessClassesCommands) {
      libraryJarCommandBuilder.addPostprocessClassesCommands(postprocessClassesCommand);
    }
    pathToClasses
        .map(RelPathSerializer::serialize)
        .ifPresent(libraryJarCommandBuilder::setPathToClasses);
  }

  @Override
  protected LibraryJarCommand buildCommand() {
    return getLibraryJarCommandBuilder().build();
  }
}
