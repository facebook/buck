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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

public class PrebuiltPythonLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements PythonPackagable {

  @AddToRuleKey private final SourcePath binarySrc;
  private final Path extractedOutput;
  private final boolean excludeDepsFromOmnibus;
  private final boolean compile;

  public PrebuiltPythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath binarySrc,
      boolean excludeDepsFromOmnibus,
      boolean compile) {
    super(buildTarget, projectFilesystem, params);
    this.binarySrc = binarySrc;
    this.extractedOutput =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "__%s__extracted");
    this.excludeDepsFromOmnibus = excludeDepsFromOmnibus;
    this.compile = compile;
  }

  @Override
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getBuildDeps();
  }

  @Override
  public Optional<PythonComponents> getPythonModules(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofModules(Objects.requireNonNull(getSourcePathToOutput())));
  }

  @Override
  public Optional<PythonComponents> getPythonResources(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofResources(
            Objects.requireNonNull(getSourcePathToOutput())));
  }

  private Optional<PythonComponents> getPythonSources() {
    return Optional.of(
        PrebuiltPythonLibraryComponents.ofSources(Objects.requireNonNull(getSourcePathToOutput())));
  }

  @Override
  public Optional<PythonComponents> getPythonBytecode(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!compile) {
      return Optional.empty();
    }
    return getPythonSources()
        .map(
            sources -> {
              PythonCompileRule compileRule =
                  (PythonCompileRule)
                      graphBuilder.requireRule(
                          getBuildTarget()
                              .withAppendedFlavors(
                                  pythonPlatform.getFlavor(),
                                  cxxPlatform.getFlavor(),
                                  PrebuiltPythonLibraryDescription.LibraryType.COMPILE
                                      .getFlavor()));
              return compileRule.getCompiledSources();
            });
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Builder<Step> builder = ImmutableList.builder();
    buildableContext.recordArtifact(extractedOutput);

    builder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), extractedOutput)));
    builder.add(
        new UnzipStep(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(binarySrc),
            extractedOutput,
            Optional.empty()));
    builder.add(new MovePythonWhlDataStep(getProjectFilesystem(), extractedOutput));
    return builder.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), extractedOutput);
  }

  @Override
  public boolean doesPythonPackageDisallowOmnibus(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return excludeDepsFromOmnibus;
  }
}
