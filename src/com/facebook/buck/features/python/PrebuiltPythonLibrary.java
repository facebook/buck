/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.features.python;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

public class PrebuiltPythonLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements PythonPackagable {

  @AddToRuleKey private final SourcePath binarySrc;
  private final Path extractedOutput;
  private final boolean excludeDepsFromOmnibus;

  public PrebuiltPythonLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath binarySrc,
      boolean excludeDepsFromOmnibus) {
    super(buildTarget, projectFilesystem, params);
    this.binarySrc = binarySrc;
    this.extractedOutput =
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "__%s__extracted");
    this.excludeDepsFromOmnibus = excludeDepsFromOmnibus;
  }

  @Override
  public Iterable<BuildRule> getPythonPackageDeps(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    return getBuildDeps();
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(
      PythonPlatform pythonPlatform, CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    // TODO(mikekap): Allow varying sources by cxx platform (in cases of prebuilt
    // extension modules).
    return PythonPackageComponents.of(
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableMap.of(),
        ImmutableSetMultimap.of(Paths.get(""), Objects.requireNonNull(getSourcePathToOutput())),
        Optional.empty());
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
    return builder.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), extractedOutput);
  }

  @Override
  public boolean doesPythonPackageDisallowOmnibus() {
    return excludeDepsFromOmnibus;
  }
}
