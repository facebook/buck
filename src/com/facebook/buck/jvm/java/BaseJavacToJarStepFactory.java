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

package com.facebook.buck.jvm.java;

import com.facebook.buck.cd.model.java.FilesystemParams;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Java implementation of compile to jar steps factory that doesn't depend on internal build graph
 * datastructures, and only knows how to create compile steps.
 */
public class BaseJavacToJarStepFactory extends CompileToJarStepFactory<JavaExtraParams> {
  public BaseJavacToJarStepFactory(boolean hasAnnotationProcessing, boolean withDownwardApi) {
    super(hasAnnotationProcessing, withDownwardApi);
  }

  @Override
  public Class<JavaExtraParams> getExtraParamsType() {
    return JavaExtraParams.class;
  }

  @Override
  public void createCompileStep(
      FilesystemParams filesystemParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      BuildTargetValue invokingRule,
      CompilerOutputPathsValue compilerOutputPathsValue,
      CompilerParameters parameters,
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      ResolvedJavac resolvedJavac,
      JavaExtraParams extraParams) {

    CompilerOutputPaths outputPath = compilerOutputPathsValue.getByType(invokingRule.getType());
    addAnnotationGenFolderStep(steps, buildableContext, outputPath.getAnnotationPath());

    ResolvedJavacOptions resolvedJavacOptions = extraParams.getResolvedJavacOptions();
    steps.add(
        new JavacStep(
            resolvedJavac,
            resolvedJavacOptions,
            invokingRule,
            getConfiguredBuckOut(filesystemParams),
            compilerOutputPathsValue,
            parameters,
            null,
            null,
            withDownwardApi,
            cellToPathMappings));
  }

  protected void addAnnotationGenFolderStep(
      ImmutableList.Builder<IsolatedStep> steps,
      BuildableContext buildableContext,
      RelPath annotationGenFolder) {
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(annotationGenFolder));
    buildableContext.recordArtifact(annotationGenFolder.getPath());
  }

  protected RelPath getConfiguredBuckOut(FilesystemParams filesystemParams) {
    return RelPath.get(filesystemParams.getConfiguredBuckOut().getPath());
  }
}
