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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.javacd.model.LibraryJarCommand;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilderBase;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;

/** JavaCD implementation of {@link LibraryStepsBuilderBase} interface. */
abstract class JavaCDLibraryCompileStepsBuilder<T extends Message> extends JavaCDStepsBuilderBase<T>
    implements LibraryStepsBuilderBase {

  private final MessageOrBuilder commandBuilder;

  protected JavaCDLibraryCompileStepsBuilder(
      boolean hasAnnotationProcessing,
      BuildJavaCommand.SpoolMode spoolMode,
      boolean withDownwardApi,
      Type type,
      MessageOrBuilder commandBuilder,
      JavaCDParams javaCDParams) {
    super(hasAnnotationProcessing, spoolMode, withDownwardApi, type, javaCDParams);
    this.commandBuilder = commandBuilder;
  }

  @Override
  public void addUnusedDependencyStep(
      UnusedDependenciesParams unusedDependenciesParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      String buildTargetFullyQualifiedName) {
    getLibraryJarCommandBuilder().setUnusedDependenciesParams(unusedDependenciesParams);
  }

  @Override
  public void addMakeMissingOutputsStep(
      RelPath rootOutput, RelPath pathToClassHashes, RelPath annotationsPath) {
    LibraryJarCommand.Builder libraryJarCommandBuilder = getLibraryJarCommandBuilder();
    libraryJarCommandBuilder.setRootOutput(RelPathSerializer.serialize(rootOutput));
    libraryJarCommandBuilder.setPathToClassHashes(RelPathSerializer.serialize(pathToClassHashes));
    libraryJarCommandBuilder.setAnnotationsPath(RelPathSerializer.serialize(annotationsPath));
  }

  protected LibraryJarCommand.Builder getLibraryJarCommandBuilder() {
    Preconditions.checkState(type == Type.LIBRARY_JAR);
    return (LibraryJarCommand.Builder) commandBuilder;
  }
}
