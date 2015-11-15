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
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.jvm.java.AccumulateClassNamesStep;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacStep;
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
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Packages the resources using {@code aapt}.
 */
public class AaptPackageResources extends AbstractBuildRule
    implements InitializableFromDisk<BuildOutput>, HasJavaClassHashes {

  public static final String RESOURCE_PACKAGE_HASH_KEY = "resource_package_hash";
  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";
  public static final String FILTERED_RESOURCE_DIRS_KEY = "filtered_resource_dirs";

  /** Options to use with {@link com.facebook.buck.android.DxStep} when dexing R.java. */
  public static final EnumSet<DxStep.Option> DX_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.RUN_IN_PROCESS,
      DxStep.Option.NO_OPTIMIZE);

  @AddToRuleKey
  private final SourcePath manifest;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final ImmutableSet<SourcePath> assetsDirectories;
  @AddToRuleKey
  private final Optional<String> resourceUnionPackage;
  @AddToRuleKey
  private final PackageType packageType;
  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private final JavacOptions javacOptions;
  @AddToRuleKey
  private final boolean rDotJavaNeedsDexing;
  @AddToRuleKey
  private final boolean shouldBuildStringSourceMap;
  @AddToRuleKey
  private final boolean skipCrunchPngs;
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
      JavacOptions javacOptions,
      boolean rDotJavaNeedsDexing,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs) {
    super(params, resolver);
    this.manifest = manifest;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.resourceDeps = resourceDeps;
    this.assetsDirectories = assetsDirectories;
    this.resourceUnionPackage = resourceUnionPackage;
    this.packageType = packageType;
    this.javacOptions = javacOptions;
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public Path getPathToOutput() {
    return getResourceApkPath();
  }

  public Optional<DexWithClasses> getRDotJavaDexWithClasses() {
    Preconditions.checkState(rDotJavaNeedsDexing,
        "Error trying to get R.java dex file: R.java is not supposed to be dexed.");

    final Optional<Integer> linearAllocSizeEstimate =
        buildOutputInitializer.getBuildOutput().rDotJavaDexLinearAllocEstimate;
    if (!linearAllocSizeEstimate.isPresent()) {
      return Optional.absent();
    }

    return Optional.<DexWithClasses>of(
        new DexWithClasses() {
          @Override
          public Path getPathToDexFile() {
            return getPathToRDotJavaDex();
          }

          @Override
          public ImmutableSet<String> getClassNames() {
            throw new RuntimeException(
                "Since R.java is unconditionally packed in the primary dex, no" +
                    "one should call this method.");
          }

          @Override
          public Sha1HashCode getClassesHash() {
            return Sha1HashCode.fromHashCode(
                Hashing.combineOrdered(getClassNamesToHashes().values()));
          }

          @Override
          public int getSizeEstimate() {
            return linearAllocSizeEstimate.get();
          }
        });
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().rDotJavaClassesHash;
  }

  /**
   * @return path to the directory where the {@code R.class} files can be found after this rule is
   *     built.
   */
  public Path getPathToCompiledRDotJavaFiles() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s_rdotjava_bin__");
  }

  public Path getPathToRDotTxtDir() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s_res_symbols__");
  }

  private Path getPathToRDotTxtFile() {
    return getPathToRDotTxtDir().resolve("R.txt");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    steps.add(
        new MkdirAndSymlinkFileStep(
            getProjectFilesystem(),
            getResolver().getAbsolutePath(manifest),
            getAndroidManifestXml()));

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
            !skipCrunchPngs /* && packageType.isCrunchPngFiles() */));

    // If we had an empty res directory, we won't generate an R.txt file.  This ensures that it
    // always exists.
    steps.add(new TouchStep(getProjectFilesystem(), getPathToRDotTxtFile()));

    if (!filteredResourcesProvider.getResDirectories().isEmpty()) {
      generateAndCompileRDotJavaFiles(steps, buildableContext);
      if (rDotJavaNeedsDexing) {
        Path rDotJavaDexDir = getPathToRDotJavaDexFiles();
        steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaDexDir));
        steps.add(
            new DxStep(
                getProjectFilesystem(),
                getPathToRDotJavaDex(),
                Collections.singleton(getPathToCompiledRDotJavaFiles()),
                DX_OPTIONS));

        // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
        // the output non-deterministic.  So use an additional scrubbing step to zero these out.
        steps.add(new ZipScrubberStep(getProjectFilesystem(), getPathToRDotJavaDex()));

        final EstimateLinearAllocStep estimateLinearAllocStep = new EstimateLinearAllocStep(
            getProjectFilesystem(),
            getPathToCompiledRDotJavaFiles());
        steps.add(estimateLinearAllocStep);

        buildableContext.recordArtifact(getPathToRDotJavaDex());
        steps.add(
            new AbstractExecutionStep("record_build_output") {
              @Override
              public int execute(ExecutionContext context) {
                buildableContext.addMetadata(
                    R_DOT_JAVA_LINEAR_ALLOC_SIZE,
                    estimateLinearAllocStep.get().toString());
                return 0;
              }
            });
      }
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

  private void generateAndCompileRDotJavaFiles(
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaSrc));

    Path rDotTxtDir = getPathToRDotTxtDir();
    MergeAndroidResourcesStep mergeStep = MergeAndroidResourcesStep.createStepForUberRDotJava(
        getProjectFilesystem(),
        resourceDeps,
        getPathToRDotTxtFile(),
        rDotJavaSrc,
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

    // Create the path where the R.java files will be compiled.
    Path rDotJavaBin = getPathToCompiledRDotJavaFiles();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaBin));

    JavacStep javacStep = RDotJava.createJavacStepForUberRDotJavaFiles(
        mergeStep.getRDotJavaFiles(),
        rDotJavaBin,
        javacOptions,
        getBuildTarget(),
        getResolver(),
        getProjectFilesystem());
    steps.add(javacStep);

    Path rDotJavaClassesTxt = getPathToRDotJavaClassesTxt();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), rDotJavaClassesTxt.getParent()));
    steps.add(new AccumulateClassNamesStep(
            getProjectFilesystem(),
            Optional.of(rDotJavaBin),
            rDotJavaClassesTxt));

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifact(rDotTxtDir);
    buildableContext.recordArtifact(rDotJavaSrc);
    buildableContext.recordArtifact(rDotJavaBin);
    buildableContext.recordArtifact(rDotJavaClassesTxt);
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
    return BuildTargets.getScratchPath(getBuildTarget(), "__manifest_%s__/AndroidManifest.xml");
  }

  /**
   * @return Path to the unsigned APK generated by this {@link com.facebook.buck.rules.BuildRule}.
   */
  public Path getResourceApkPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.unsigned.ap_");
  }

  public Sha1HashCode getResourcePackageHash() {
    return buildOutputInitializer.getBuildOutput().resourcePackageHash;
  }

  static class BuildOutput {
    private final Sha1HashCode resourcePackageHash;
    private final Optional<Integer> rDotJavaDexLinearAllocEstimate;
    private final ImmutableSortedMap<String, HashCode> rDotJavaClassesHash;

    BuildOutput(
        Sha1HashCode resourcePackageHash,
        Optional<Integer> rDotJavaDexLinearAllocEstimate,
        ImmutableSortedMap<String, HashCode> rDotJavaClassesHash) {
      this.resourcePackageHash = resourcePackageHash;
      this.rDotJavaDexLinearAllocEstimate = rDotJavaDexLinearAllocEstimate;
      this.rDotJavaClassesHash = rDotJavaClassesHash;
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

    ImmutableSortedMap<String, HashCode> classesHash = ImmutableSortedMap.of();
    if (!filteredResourceDirs.get().isEmpty()) {
      List<String> lines =
          onDiskBuildInfo.getOutputFileContentsByLine(getPathToRDotJavaClassesTxt());
      classesHash = AccumulateClassNamesStep.parseClassHashes(lines);
    }

    Optional<String> linearAllocSizeValue = onDiskBuildInfo.getValue(R_DOT_JAVA_LINEAR_ALLOC_SIZE);
    Optional<Integer> linearAllocSize = linearAllocSizeValue.isPresent()
        ? Optional.of(Integer.parseInt(linearAllocSizeValue.get()))
        : Optional.<Integer>absent();

    return new BuildOutput(resourcePackageHash.get(), linearAllocSize, classesHash);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  private Path getPathToRDotJavaDexFiles() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s_rdotjava_dex__");
  }

  private Path getPathToRDotJavaClassesTxt() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s_rdotjava_classes__")
        .resolve("classes.txt");
  }

  private Path getPathToRDotJavaDex() {
    return getPathToRDotJavaDexFiles().resolve("classes.dex.jar");
  }

  /**
   * This directory contains both the generated {@code R.java} files under a directory path that
   * matches the corresponding package structure.
   */
  private Path getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget());
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s_string_source_map__");
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
    return BuildTargets.getGenPath(getBuildTarget(), "__%s__proguard__").resolve(".proguard");
  }

  @VisibleForTesting
  static Path getPathToGeneratedRDotJavaSrcFiles(BuildTarget buildTarget) {
    return BuildTargets.getScratchPath(buildTarget, "__%s_rdotjava_src__");
  }

  @VisibleForTesting
  FilteredResourcesProvider getFilteredResourcesProvider() {
    return filteredResourcesProvider;
  }
}
