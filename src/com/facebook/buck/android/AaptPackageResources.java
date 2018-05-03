/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Packages the resources using {@code aapt}. */
public class AaptPackageResources extends AbstractBuildRule {

  public static final String RESOURCE_APK_PATH_FORMAT = "%s.unsigned.ap_";

  @AddToRuleKey private final SourcePath manifest;
  private final FilteredResourcesProvider filteredResourcesProvider;
  @AddToRuleKey private final boolean skipCrunchPngs;
  @AddToRuleKey private final ManifestEntries manifestEntries;
  @AddToRuleKey private final boolean includesVectorDrawables;
  @AddToRuleKey private final ImmutableList<SourcePath> dependencyResourceApks;

  private final AndroidPlatformTarget androidPlatformTarget;
  private final Supplier<SortedSet<BuildRule>> buildDepsSupplier;

  static ImmutableSortedSet<BuildRule> getAllDeps(
      BuildTarget aaptTarget,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver ruleResolver,
      SourcePath manifest,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableList<SourcePath> dependencyResourceApks,
      ImmutableList<HasAndroidResourceDeps> resourceDeps) {
    ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
    Stream<BuildTarget> resourceTargets =
        resourceDeps.stream().map(HasAndroidResourceDeps::getBuildTarget);
    depsBuilder.addAll(
        BuildRules.toBuildRulesFor(aaptTarget, ruleResolver, resourceTargets::iterator));
    depsBuilder.addAll(
        resourceDeps
            .stream()
            .map(HasAndroidResourceDeps::getRes)
            .flatMap(ruleFinder.FILTER_BUILD_RULE_INPUTS)
            .iterator());
    for (SourcePath apk : dependencyResourceApks) {
      ruleFinder.getRule(apk).ifPresent(depsBuilder::add);
    }
    ruleFinder.getRule(manifest).ifPresent(depsBuilder::add);
    filteredResourcesProvider.getResourceFilterRule().ifPresent(depsBuilder::add);
    return depsBuilder.build();
  }

  AaptPackageResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver ruleResolver,
      SourcePath manifest,
      ImmutableList<SourcePath> dependencyResourceApks,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableList<HasAndroidResourceDeps> resourceDeps,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      ManifestEntries manifestEntries) {
    super(buildTarget, projectFilesystem);
    this.androidPlatformTarget = androidPlatformTarget;
    this.manifest = manifest;
    this.dependencyResourceApks = dependencyResourceApks;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.skipCrunchPngs = skipCrunchPngs;
    this.includesVectorDrawables = includesVectorDrawables;
    this.manifestEntries = manifestEntries;
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                getAllDeps(
                    buildTarget,
                    ruleFinder,
                    ruleResolver,
                    manifest,
                    filteredResourcesProvider,
                    dependencyResourceApks,
                    resourceDeps));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getResourceApkPath());
  }

  private Path getPathToRDotTxtDir() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__%s_res_symbols__");
  }

  private Path getPathToRDotTxtFile() {
    return getPathToRDotTxtDir().resolve("R.txt");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    prepareManifestForAapt(
        context,
        steps,
        getProjectFilesystem(),
        getAndroidManifestXml(),
        context.getSourcePathResolver().getAbsolutePath(manifest),
        manifestEntries);

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                getResourceApkPath().getParent())));

    Path rDotTxtDir = getPathToRDotTxtDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), rDotTxtDir)));

    Path pathToGeneratedProguardConfig = getPathToGeneratedProguardConfigFile();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                pathToGeneratedProguardConfig.getParent())));
    buildableContext.recordArtifact(pathToGeneratedProguardConfig);

    steps.add(
        new AaptStep(
            getBuildTarget(),
            androidPlatformTarget,
            getProjectFilesystem().getRootPath(),
            getAndroidManifestXml(),
            filteredResourcesProvider.getRelativeResDirectories(
                getProjectFilesystem(), context.getSourcePathResolver()),
            ImmutableSortedSet.of(),
            getResourceApkPath(),
            rDotTxtDir,
            pathToGeneratedProguardConfig,
            dependencyResourceApks
                .stream()
                .map(context.getSourcePathResolver()::getAbsolutePath)
                .collect(ImmutableList.toImmutableList()),
            /*
             * In practice, it appears that if --no-crunch is used, resources will occasionally
             * appear distorted in the APK produced by this command (and what's worse, a clean
             * reinstall does not make the problem go away). This is not reliably reproducible, so
             * for now, we categorically outlaw the use of --no-crunch so that developers do not get
             * stuck in the distorted image state. One would expect the use of --no-crunch to allow
             * for faster build times, so it would be nice to figure out a way to leverage it in
             * debug mode that never results in distorted images.
             */
            !skipCrunchPngs /* && packageType.isCrunchPngFiles() */,
            includesVectorDrawables,
            manifestEntries),
        ZipScrubberStep.of(
            context.getSourcePathResolver().getAbsolutePath(getSourcePathToOutput())));

    // If we had an empty res directory, we won't generate an R.txt file.  This ensures that it
    // always exists.
    steps.add(new TouchStep(getProjectFilesystem(), getPathToRDotTxtFile()));

    buildableContext.recordArtifact(rDotTxtDir);
    buildableContext.recordArtifact(getAndroidManifestXml());
    buildableContext.recordArtifact(getResourceApkPath());

    return steps.build();
  }

  static void prepareManifestForAapt(
      BuildContext context,
      ImmutableList.Builder<Step> stepBuilder,
      ProjectFilesystem projectFilesystem,
      Path finalManifestPath,
      Path rawManifestPath,
      ManifestEntries manifestEntries) {
    // Copy manifest to a path named AndroidManifest.xml after replacing the manifest placeholders
    // if needed. Do this before running any other commands to ensure that it is available at the
    // desired path.

    stepBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), projectFilesystem, finalManifestPath.getParent())));

    Optional<ImmutableMap<String, String>> placeholders = manifestEntries.getPlaceholders();
    if (placeholders.isPresent() && !placeholders.get().isEmpty()) {
      stepBuilder.add(
          new ReplaceManifestPlaceholdersStep(
              projectFilesystem, rawManifestPath, finalManifestPath, placeholders.get()));
    } else {
      stepBuilder.add(CopyStep.forFile(projectFilesystem, rawManifestPath, finalManifestPath));
    }
  }

  /**
   * Buck does not require the manifest to be named AndroidManifest.xml, but commands such as aapt
   * do. For this reason, we symlink the path to {@link #manifest} to the path returned by this
   * method, whose name is always "AndroidManifest.xml".
   *
   * <p>Therefore, commands created by this buildable should use this method instead of {@link
   * #manifest}.
   */
  private Path getAndroidManifestXml() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__manifest_%s__/AndroidManifest.xml");
  }

  /**
   * @return Path to the unsigned APK generated by this {@link com.facebook.buck.rules.BuildRule}.
   */
  private Path getResourceApkPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), RESOURCE_APK_PATH_FORMAT);
  }

  private Path getPathToGeneratedProguardConfigFile() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/proguard/proguard.txt");
  }

  @VisibleForTesting
  FilteredResourcesProvider getFilteredResourcesProvider() {
    return filteredResourcesProvider;
  }

  public AaptOutputInfo getAaptOutputInfo() {
    BuildTarget target = getBuildTarget();
    return AaptOutputInfo.builder()
        .setPathToRDotTxt(ExplicitBuildTargetSourcePath.of(target, getPathToRDotTxtFile()))
        .setPrimaryResourcesApkPath(ExplicitBuildTargetSourcePath.of(target, getResourceApkPath()))
        .setAndroidManifestXml(ExplicitBuildTargetSourcePath.of(target, getAndroidManifestXml()))
        .setAaptGeneratedProguardConfigFile(
            ExplicitBuildTargetSourcePath.of(target, getPathToGeneratedProguardConfigFile()))
        .build();
  }
}
