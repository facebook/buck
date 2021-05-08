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

package com.facebook.buck.features.js;

import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import java.util.function.Supplier;

/** Represents a combination of a JavaScript bundle *and* Android resources. */
public class JsBundleAndroid extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements AndroidPackageable, JsBundleOutputs {

  @AddToRuleKey private final JsBundleOutputs delegate;

  @AddToRuleKey private final AndroidResource androidResource;

  public JsBundleAndroid(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      JsBundleOutputs delegate,
      AndroidResource androidResource) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.delegate = delegate;
    this.androidResource = androidResource;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter sourcePathResolverAdapter = context.getSourcePathResolver();

    buildableContext.recordArtifact(
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToOutput()).getPath());
    buildableContext.recordArtifact(
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToSourceMap()).getPath());
    buildableContext.recordArtifact(
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToResources()).getPath());
    buildableContext.recordArtifact(
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToMisc()).getPath());

    RelPath jsDir = sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToOutput());
    RelPath resourcesDir =
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToResources());
    RelPath sourceMapFile =
        sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToSourceMap());
    RelPath miscDirPath = sourcePathResolverAdapter.getCellUnsafeRelPath(getSourcePathToMisc());

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolverAdapter.getCellUnsafeRelPath(
                        JsUtil.relativeToOutputRoot(
                            getBuildTarget(), getProjectFilesystem(), "")))))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), jsDir.getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    resourcesDir.getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourceMapFile.getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    miscDirPath.getParent())),
            CopyStep.forDirectory(
                sourcePathResolverAdapter
                    .getAbsolutePath(delegate.getSourcePathToOutput())
                    .getPath(),
                jsDir.getPath().getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS),
            CopyStep.forDirectory(
                sourcePathResolverAdapter
                    .getAbsolutePath(delegate.getSourcePathToResources())
                    .getPath(),
                resourcesDir.getPath().getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS),
            CopyStep.forDirectory(
                sourcePathResolverAdapter
                    .getAbsolutePath(delegate.getSourcePathToSourceMap())
                    .getPath(),
                sourceMapFile.getPath().getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS),
            CopyStep.forDirectory(
                sourcePathResolverAdapter.getAbsolutePath(delegate.getSourcePathToMisc()).getPath(),
                miscDirPath.getPath().getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS))
        .build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms) {
    return ImmutableList.of(androidResource);
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    collector.addAssetsDirectory(getBuildTarget(), getSourcePathToOutput());
  }

  @Override
  public String getBundleName() {
    return delegate.getBundleName();
  }

  @Override
  public JsDependenciesOutputs getJsDependenciesOutputs(ActionGraphBuilder graphBuilder) {
    BuildTarget target = getBuildTarget().withAppendedFlavors(JsFlavors.DEPENDENCY_FILE);
    return (JsDependenciesOutputs) graphBuilder.requireRule(target);
  }
}
