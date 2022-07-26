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

package com.facebook.buck.android;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * An object that represents the resources prebuilt native library.
 *
 * <p>Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 *
 * <pre>
 * prebuilt_native_library(
 *   name = 'face_dot_com',
 *   native_libs = 'nativeLibs',
 * )
 * </pre>
 */
public class PrebuiltNativeLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements NativeLibraryBuildRule, AndroidPackageable {

  @AddToRuleKey private final boolean isAsset;
  @AddToRuleKey private final boolean hasWrapScript;
  @AddToRuleKey private final SourcePath nativeLibsPath;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<? extends SourcePath> librarySources;

  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final RelPath genDirectory;

  protected PrebuiltNativeLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePath nativeLibsPath,
      boolean isAsset,
      boolean hasWrapScript,
      ImmutableSortedSet<? extends SourcePath> librarySources) {
    super(buildTarget, projectFilesystem, params);

    this.librarySources = librarySources;
    this.genDirectory =
        BuildTargetPaths.getGenPath(getProjectFilesystem().getBuckPaths(), buildTarget, "__lib%s");
    this.isAsset = isAsset;
    this.hasWrapScript = hasWrapScript;
    this.nativeLibsPath = nativeLibsPath;
  }

  @Override
  public boolean isAsset() {
    return isAsset;
  }

  @Override
  public Path getLibraryPath() {
    return genDirectory.getPath();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), genDirectory);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();
    AbsPath resolvedNativePath = resolver.getAbsolutePath(nativeLibsPath);

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDirectory.getPath())));
    steps.add(
        CopyStep.forDirectory(
            resolvedNativePath.getPath(),
            genDirectory.getPath(),
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    buildableContext.recordArtifact(genDirectory.getPath());

    return steps.build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms) {
    return AndroidPackageableCollector.getPackageableRules(getDeclaredDeps());
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    if (isAsset) {
      collector.addNativeLibAssetsDirectory(
          getBuildTarget(), ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibraryPath()));
    } else if (hasWrapScript) {
      collector.addNativeLibsDirectoryForSystemLoader(
          getBuildTarget(), ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibraryPath()));
    } else {
      collector.addNativeLibsDirectory(
          getBuildTarget(), ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibraryPath()));
    }
  }
}
