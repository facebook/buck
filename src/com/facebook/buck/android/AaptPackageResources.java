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

import com.facebook.buck.android.AaptPackageResources.BuildOutput;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Packages the resources using {@code aapt}.
 */
public class AaptPackageResources extends AbstractBuildRule
    implements InitializableFromDisk<BuildOutput> {

  public static final String RESOURCE_PACKAGE_HASH_KEY = "resource_package_hash";
  public static final String FILTERED_RESOURCE_DIRS_KEY = "filtered_resource_dirs";

  @AddToRuleKey
  private final SourcePath manifest;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final ImmutableSet<SourcePath> assetsDirectories;
  @AddToRuleKey
  private final Optional<String> resourceUnionPackage;
  @AddToRuleKey
  private final PackageType packageType;
  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  @AddToRuleKey
  private final boolean shouldBuildStringSourceMap;
  @AddToRuleKey
  private final boolean skipCrunchPngs;
  @AddToRuleKey
  private final EnumSet<RType> bannedDuplicateResourceTypes;
  @AddToRuleKey
  private final ManifestEntries manifestEntries;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  AaptPackageResources(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath manifest,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableList<HasAndroidResourceDeps> resourceDeps,
      ImmutableSet<SourcePath> assetsDirectories,
      Optional<String> resourceUnionPackage,
      PackageType packageType,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs,
      EnumSet<RType> bannedDuplicateResourceTypes,
      ManifestEntries manifestEntries) {
    super(params, resolver);
    this.manifest = manifest;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.resourceDeps = resourceDeps;
    this.assetsDirectories = assetsDirectories;
    this.resourceUnionPackage = resourceUnionPackage;
    this.packageType = packageType;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.manifestEntries = manifestEntries;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public Path getPathToOutput() {
    return getResourceApkPath();
  }

  public Path getPathToRDotTxtDir() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__%s_res_symbols__");
  }

  private Path getPathToRDotTxtFile() {
    return getPathToRDotTxtDir().resolve("R.txt");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Copy manifest to a path named AndroidManifest.xml after replacing the manifest placeholders
    // if needed. Do this before running any other commands to ensure that it is available at the
    // desired path.
    steps.add(
        new MkdirStep(getProjectFilesystem(), getAndroidManifestXml().getParent()));

    Optional<ImmutableMap<String, String>> placeholders = manifestEntries.getPlaceholders();
    if (placeholders.isPresent() && !placeholders.get().isEmpty()) {
      steps.add(
          new ReplaceManifestPlaceholdersStep(
              getProjectFilesystem(),
              getResolver().getAbsolutePath(manifest),
              getAndroidManifestXml(),
              placeholders.get()));
    } else {
      steps.add(
          CopyStep.forFile(
              getProjectFilesystem(),
              getResolver().getAbsolutePath(manifest),
              getAndroidManifestXml()));
    }

    steps.add(new MkdirStep(getProjectFilesystem(), getResourceApkPath().getParent()));

    Path rDotTxtDir = getPathToRDotTxtDir();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotTxtDir));

    Optional<Path> pathToGeneratedProguardConfig = Optional.absent();
    if (packageType.isBuildWithObfuscation()) {
      Path proguardConfigDir = getPathToGeneratedProguardConfigDir();
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), proguardConfigDir));
      pathToGeneratedProguardConfig = Optional.of(proguardConfigDir.resolve("proguard.txt"));
      buildableContext.recordArtifact(proguardConfigDir);
    }

    steps.add(
        new AaptStep(
            getProjectFilesystem().getRootPath(),
            getAndroidManifestXml(),
            filteredResourcesProvider.getResDirectories(),
            FluentIterable.from(getResolver().getAllAbsolutePaths(assetsDirectories))
                .toSortedSet(Ordering.<Path>natural()),
            getResourceApkPath(),
            rDotTxtDir,
            pathToGeneratedProguardConfig,
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
            manifestEntries));

    // If we had an empty res directory, we won't generate an R.txt file.  This ensures that it
    // always exists.
    steps.add(new TouchStep(getProjectFilesystem(), getPathToRDotTxtFile()));

    if (hasRDotJava()) {
      generateRDotJavaFiles(steps, buildableContext);
    }

    // Record the filtered resources dirs, since when we initialize ourselves from disk, we'll
    // need to test whether this is empty or not without requiring the `ResourcesFilter` rule to
    // be available.
    buildableContext.addMetadata(
        FILTERED_RESOURCE_DIRS_KEY,
        FluentIterable.from(filteredResourcesProvider.getResDirectories())
            .transform(Functions.toStringFunction())
            .toSortedList(Ordering.natural()));

    buildableContext.recordArtifact(getAndroidManifestXml());
    buildableContext.recordArtifact(getResourceApkPath());

    steps.add(
        new RecordFileSha1Step(
            getProjectFilesystem(),
            getResourceApkPath(),
            RESOURCE_PACKAGE_HASH_KEY,
            buildableContext));

    return steps.build();
  }

  /**
   * True iff an app has resources with ids (after filtering (like for display density)).
   */
  boolean hasRDotJava() {
    return !filteredResourcesProvider.getResDirectories().isEmpty();
  }

  private void generateRDotJavaFiles(
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaSrc));

    Path rDotTxtDir = getPathToRDotTxtDir();
    MergeAndroidResourcesStep mergeStep = MergeAndroidResourcesStep.createStepForUberRDotJava(
        getProjectFilesystem(),
        getResolver(),
        resourceDeps,
        getPathToRDotTxtFile(),
        rDotJavaSrc,
        bannedDuplicateResourceTypes,
        resourceUnionPackage);
    steps.add(mergeStep);

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo = new GenStringSourceMapStep(
          getProjectFilesystem(),
          rDotTxtDir,
          filteredResourcesProvider.getResDirectories(),
          outputDirPath);
      steps.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifact(outputDirPath);
    }

    // Ensure the generated R.txt and R.java files are also recorded.
    buildableContext.recordArtifact(rDotTxtDir);
    buildableContext.recordArtifact(rDotJavaSrc);
  }

  /**
   * Buck does not require the manifest to be named AndroidManifest.xml, but commands such as aapt
   * do. For this reason, we symlink the path to {@link #manifest} to the path returned by
   * this method, whose name is always "AndroidManifest.xml".
   * <p>
   * Therefore, commands created by this buildable should use this method instead of
   * {@link #manifest}.
   */
  Path getAndroidManifestXml() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__manifest_%s__/AndroidManifest.xml");
  }

  /**
   * @return Path to the unsigned APK generated by this {@link com.facebook.buck.rules.BuildRule}.
   */
  public Path getResourceApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.unsigned.ap_");
  }

  public Sha1HashCode getResourcePackageHash() {
    return buildOutputInitializer.getBuildOutput().resourcePackageHash;
  }

  static class BuildOutput {
    private final Sha1HashCode resourcePackageHash;

    BuildOutput(Sha1HashCode resourcePackageHash) {
      this.resourcePackageHash = resourcePackageHash;
    }
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    Optional<Sha1HashCode> resourcePackageHash = onDiskBuildInfo.getHash(RESOURCE_PACKAGE_HASH_KEY);
    Preconditions.checkState(
        resourcePackageHash.isPresent(),
        "Should not be initializing %s from disk if the resource hash is not written.",
        getBuildTarget());

    Optional<ImmutableList<String>> filteredResourceDirs =
        onDiskBuildInfo.getValues(FILTERED_RESOURCE_DIRS_KEY);
    Preconditions.checkState(
        filteredResourceDirs.isPresent(),
        "Should not be initializing %s from disk if the filtered resources dirs are not written.",
        getBuildTarget());

    return new BuildOutput(resourcePackageHash.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  /**
   * This directory contains both the generated {@code R.java} files under a directory path that
   * matches the corresponding package structure.
   */
  Path getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget(), getProjectFilesystem());
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__%s_string_source_map__");
  }

  /**
   * This is the path to the directory for generated files related to ProGuard. Ultimately, it
   * should include:
   * <ul>
   *   <li>proguard.txt
   *   <li>dump.txt
   *   <li>seeds.txt
   *   <li>usage.txt
   *   <li>mapping.txt
   *   <li>obfuscated.jar
   * </ul>
   * @return path to directory (will not include trailing slash)
   */
  public Path getPathToGeneratedProguardConfigDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__proguard__")
        .resolve(".proguard");
  }

  @VisibleForTesting
  static Path getPathToGeneratedRDotJavaSrcFiles(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  @VisibleForTesting
  FilteredResourcesProvider getFilteredResourcesProvider() {
    return filteredResourcesProvider;
  }
}
