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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.DefaultFieldDeps;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Constructs a single merged dex file from pre-dexed inputs */
public class PreDexSingleDexMerge extends PreDexMerge {
  private final Collection<DexProducedFromJavaLibrary> preDexDeps;

  @ExcludeFromRuleKey(
      reason = "downward API doesn't affect the result of rule's execution",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class,
      deps = DefaultFieldDeps.class)
  private final boolean withDownwardApi;

  public PreDexSingleDexMerge(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AndroidPlatformTarget androidPlatformTarget,
      String dexTool,
      Collection<DexProducedFromJavaLibrary> preDexDeps,
      boolean withDownwardApi) {
    super(buildTarget, projectFilesystem, params, androidPlatformTarget, dexTool);
    this.preDexDeps = preDexDeps;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public DexFilesInfo getDexFilesInfo() {
    return new DexFilesInfo(
        getSourcePathToPrimaryDex(), ImmutableSortedSet.of(), Optional.empty(), ImmutableMap.of());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getPrimaryDexRoot())));

    // For single-dex apps with pre-dexing, we just add the steps directly.
    Stream<SourcePath> sourcePathsToDex =
        preDexDeps.stream()
            .filter(DexProducedFromJavaLibrary::hasOutput)
            .map(DexProducedFromJavaLibrary::getSourcePathToDex);

    Path primaryDexPath = getPrimaryDexPath();
    buildableContext.recordArtifact(primaryDexPath);

    ImmutableSortedSet<AbsPath> filesToDex =
        context
            .getSourcePathResolver()
            .getAllAbsolutePaths(sourcePathsToDex.collect(Collectors.toList()));

    // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
    steps.add(
        new DxStep(
            getProjectFilesystem(),
            androidPlatformTarget,
            primaryDexPath,
            filesToDex.stream().map(AbsPath::getPath).collect(ImmutableList.toImmutableList()),
            DX_MERGE_OPTIONS,
            dexTool,
            withDownwardApi));

    return steps.build();
  }
}
