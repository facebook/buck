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

import static com.facebook.buck.android.BinaryType.AAB;
import static com.facebook.buck.android.BinaryType.APK;

import com.facebook.buck.android.redex.ReDexStep;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;

/**
 * A {@link BuildRule} that is used to create a universal APK from a bundle. It creates the APK then
 * runs redex on it.
 */
public class CreateReDexedApkFromAAB extends AbstractBuildRule implements HasInstallableApk {

  @Override
  public ApkInfo getApkInfo() {
    return ImmutableApkInfo.ofImpl(manifestPath, getSourcePathToOutput(), Optional.empty());
  }

  private final BuildRuleParams buildRuleParams;

  private final AndroidSdkLocation androidSdkLocation;
  @AddToRuleKey private final BuildRule dexMergeRule;
  @AddToRuleKey private final Tool aapt2Tool;
  @AddToRuleKey private final RedexOptions redexOptions;
  @AddToRuleKey private final boolean withDownwardApi;
  @AddToRuleKey protected final SourcePath keystorePath;
  @AddToRuleKey private final SourcePath keystorePropertiesPath;
  @AddToRuleKey private final SourcePath manifestPath;

  CreateReDexedApkFromAAB(
      BuildRule dexMergeRule,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      RedexOptions redexOptions,
      ProjectFilesystem filesystem,
      AndroidSdkLocation androidSdkLocation,
      Tool aapt2Tool,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      boolean withDownwardApi,
      SourcePath manifestPath) {
    super(buildTarget, filesystem);
    params =
        new BuildRuleParams(
            ImmutableSortedSet::of,
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
            params.getTargetGraphOnlyDeps());
    this.buildRuleParams = params;
    this.dexMergeRule = dexMergeRule;
    this.androidSdkLocation = androidSdkLocation;
    this.aapt2Tool = aapt2Tool;
    this.redexOptions = redexOptions;
    this.keystorePath = keystorePath;
    this.keystorePropertiesPath = keystorePropertiesPath;
    this.withDownwardApi = withDownwardApi;
    this.manifestPath = manifestPath;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildRuleParams.getBuildDeps();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path aabPath =
        AndroidBinaryPathUtility.getFinalApkPath(
            getProjectFilesystem(), dexMergeRule.getBuildTarget(), AAB);
    RelPath apksFile = getApksFile();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), apksFile.getParent())));

    // Assumes the path to the actual tool is the first item in the list?
    String aapt2Path = aapt2Tool.getCommandPrefix(context.getSourcePathResolver()).get(0);

    // First invoke bundletool build-apks - > this creates an apks which has universal.apk inside
    steps.add(
        new BuildApksStep(
            getProjectFilesystem(), aabPath, apksFile.getPath(), AbsPath.get(aapt2Path).getPath()));

    // Now take the apks which has universal.apk as one of the items and extract the zip
    RelPath apksExtractedDir = getApksExtractedDir();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), apksExtractedDir)));
    steps.add(
        new UnzipStep(
            getProjectFilesystem(),
            apksFile.getPath(),
            apksExtractedDir.getPath(),
            Optional.empty()));

    SourcePathResolverAdapter resolver = context.getSourcePathResolver();
    AbsPath pathToKeystore = resolver.getAbsolutePath(keystorePath);
    Supplier<KeystoreProperties> keystoreProperties =
        getKeystorePropertiesSupplier(resolver, pathToKeystore);

    // Finally run redex on the extracted universal.apk
    Path outputPath =
        AndroidBinaryPathUtility.getFinalApkPath(getProjectFilesystem(), getBuildTarget(), APK);
    steps.addAll(
        createRedexSteps(
            context,
            buildableContext,
            context.getSourcePathResolver(),
            keystoreProperties,
            getUnRedexedApk().getPath(),
            outputPath));

    buildableContext.recordArtifact(outputPath.getParent());
    return steps.build();
  }

  private Supplier<KeystoreProperties> getKeystorePropertiesSupplier(
      SourcePathResolverAdapter resolver, AbsPath pathToKeystore) {
    return MoreSuppliers.memoize(
        () -> {
          try {
            return KeystoreProperties.createFromPropertiesFile(
                pathToKeystore.getPath(),
                resolver.getAbsolutePath(keystorePropertiesPath).getPath(),
                getProjectFilesystem());
          } catch (IOException e) {
            throw new RuntimeException();
          }
        });
  }

  private RelPath getApksFile() {
    return BuildPaths.getScratchDir(getProjectFilesystem(), getBuildTarget())
        .resolveRel("extracted.apks");
  }

  private RelPath getApksExtractedDir() {
    return BuildPaths.getScratchDir(getProjectFilesystem(), getBuildTarget())
        .resolveRel("extracted_apks");
  }

  private RelPath getUnRedexedApk() {
    return getApksExtractedDir().resolveRel("universal.apk");
  }

  /**
   * Directory of text files used by proguard. Unfortunately, this contains both inputs and outputs.
   */
  private RelPath getProguardTextFilesPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem().getBuckPaths(), dexMergeRule.getBuildTarget(), "%s/proguard");
  }

  private Iterable<Step> createRedexSteps(
      BuildContext context,
      BuildableContext buildableContext,
      SourcePathResolverAdapter resolver,
      Supplier<KeystoreProperties> keystoreProperties,
      Path apkToRedexAndAlign,
      Path redexedApk) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    RelPath proguardConfigDir = getProguardTextFilesPath();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), redexedApk.getParent())));
    ImmutableList<Step> redexSteps =
        ReDexStep.createSteps(
            getProjectFilesystem(),
            ProjectFilesystemUtils.relativize(
                getProjectFilesystem().getRootPath(), context.getBuildCellRootPath()),
            androidSdkLocation,
            resolver,
            redexOptions,
            apkToRedexAndAlign,
            redexedApk,
            keystoreProperties,
            proguardConfigDir.getPath(),
            buildableContext,
            withDownwardApi);
    steps.addAll(redexSteps);
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        AndroidBinaryPathUtility.getFinalApkPath(getProjectFilesystem(), getBuildTarget(), APK));
  }
}
