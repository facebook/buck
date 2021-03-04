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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.FilesystemParamsUtils;
import com.facebook.buck.jvm.java.JavacPipelineState;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarPipelineStepsBuilder;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/** Default implementation of {@link LibraryJarPipelineStepsBuilder} */
class DefaultLibraryJarPipelineStepsBuilder<T extends CompileToJarStepFactory.ExtraParams>
    extends DefaultLibraryStepsBuilderBase<T> implements LibraryJarPipelineStepsBuilder {

  DefaultLibraryJarPipelineStepsBuilder(CompileToJarStepFactory<T> configuredCompiler) {
    super(configuredCompiler);
  }

  @Override
  public void addPipelinedBuildStepsForLibraryJar(
      BuildTargetValue libraryTarget,
      FilesystemParams filesystemParams,
      BuildableContext buildableContext,
      JavacPipelineState state,
      CompilerOutputPathsValue compilerOutputPathsValue,
      RelPath pathToClassHashes,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      Optional<RelPath> pathToClasses) {
    ((BaseJavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            filesystemParams,
            cellToPathMappings,
            libraryTarget,
            state,
            compilerOutputPathsValue,
            stepsBuilder,
            buildableContext,
            resourcesMap);

    JavaLibraryRules.addAccumulateClassNamesStep(
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams),
        stepsBuilder,
        pathToClasses,
        pathToClassHashes);
  }
}