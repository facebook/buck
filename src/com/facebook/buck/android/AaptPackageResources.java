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
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.AccumulateClassNamesStep;
import com.facebook.buck.java.HasJavaClassHashes;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.JavacStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Packages the resources using {@code aapt}.
 */
public class AaptPackageResources extends AbstractBuildRule
    implements InitializableFromDisk<BuildOutput>, HasJavaClassHashes {

  public static final String RESOURCE_PACKAGE_HASH_KEY = "resource_package_hash";
  public static final String R_DOT_JAVA_LINEAR_ALLOC_SIZE = "r_dot_java_linear_alloc_size";

  /** Options to use with {@link com.facebook.buck.android.DxStep} when dexing R.java. */
  public static final EnumSet<DxStep.Option> DX_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.RUN_IN_PROCESS,
      DxStep.Option.NO_OPTIMIZE);

  private final SourcePath manifest;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final ImmutableSet<Path> assetsDirectories;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private final JavacOptions javacOptions;
  private final boolean rDotJavaNeedsDexing;
  private final boolean shouldBuildStringSourceMap;
  private final boolean shouldWarnIfMissingResource;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  AaptPackageResources(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath manifest,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableList<HasAndroidResourceDeps> resourceDeps,
      ImmutableSet<Path> assetsDirectories,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      JavacOptions javacOptions,
      boolean rDotJavaNeedsDexing,
      boolean shouldBuildStringSourceMap,
      boolean shouldWarnIfMissingResources) {
    super(params, resolver);
    this.manifest = manifest;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.resourceDeps = resourceDeps;
    this.assetsDirectories = assetsDirectories;
    this.packageType = packageType;
    this.cpuFilters = cpuFilters;
    this.javacOptions = javacOptions;
    this.rDotJavaNeedsDexing = rDotJavaNeedsDexing;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.shouldWarnIfMissingResource = shouldWarnIfMissingResources;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(Collections.singleton(manifest));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("packageType", packageType.toString())
        .setReflectively("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters).toString())
        .setReflectively("rDotJavaNeedsDexing", rDotJavaNeedsDexing)
        .setReflectively("shouldBuildStringSourceMap", shouldBuildStringSourceMap);
  }

  @Override
  public Path getPathToOutputFile() {
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
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_rdotjava_bin__");
  }

  public Path getPathToRDotTxtDir() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_res_symbols__");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    steps.add(
        new MkdirAndSymlinkFileStep(getResolver().getPath(manifest), getAndroidManifestXml()));

    // Copy the transitive closure of files in assets to a single directory, if any.
    // TODO(mbolin): Older versions of aapt did not support multiple -A flags, so we can probably
    // eliminate this now.
    Step collectAssets = new Step() {
      @Override
      public int execute(ExecutionContext context) throws IOException, InterruptedException {
        // This must be done in a Command because the files and directories that are specified may
        // not exist at the time this Command is created because the previous Commands have not run
        // yet.
        ImmutableList.Builder<Step> commands = ImmutableList.builder();
        try {
          createAllAssetsDirectory(
              assetsDirectories,
              commands,
              context.getProjectFilesystem());
        } catch (IOException e) {
          context.logError(e, "Error creating all assets directory in %s.", getBuildTarget());
          return 1;
        }

        for (Step command : commands.build()) {
          int exitCode = command.execute(context);
          if (exitCode != 0) {
            throw new HumanReadableException("Error running " + command.getDescription(context));
          }
        }

        return 0;
      }

      @Override
      public String getShortName() {
        return "symlink_assets";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return getShortName();
      }
    };
    steps.add(collectAssets);

    Optional<Path> assetsDirectory;
    if (assetsDirectories.isEmpty()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    steps.add(new MkdirStep(getResourceApkPath().getParent()));

    Path rDotTxtDir = getPathToRDotTxtDir();
    steps.add(new MakeCleanDirectoryStep(rDotTxtDir));

    Optional<Path> pathToGeneratedProguardConfig = Optional.absent();
    if (packageType.isBuildWithObfuscation()) {
      Path proguardConfigDir = getPathToGeneratedProguardConfigDir();
      steps.add(new MakeCleanDirectoryStep(proguardConfigDir));
      pathToGeneratedProguardConfig = Optional.of(proguardConfigDir.resolve("proguard.txt"));
      buildableContext.recordArtifactsInDirectory(proguardConfigDir);
    }

    steps.add(new AaptStep(
        getAndroidManifestXml(),
        filteredResourcesProvider.getResDirectories(),
        assetsDirectory,
        getResourceApkPath(),
        rDotTxtDir,
        pathToGeneratedProguardConfig,
        packageType.isCrunchPngFiles()));

    if (!filteredResourcesProvider.getResDirectories().isEmpty()) {
      generateAndCompileRDotJavaFiles(steps, buildableContext);
      if (rDotJavaNeedsDexing) {
        Path rDotJavaDexDir = getPathToRDotJavaDexFiles();
        steps.add(new MakeCleanDirectoryStep(rDotJavaDexDir));
        steps.add(new DxStep(
                getPathToRDotJavaDex(),
                Collections.singleton(getPathToCompiledRDotJavaFiles()),
                DX_OPTIONS));

        final EstimateLinearAllocStep estimateLinearAllocStep = new EstimateLinearAllocStep(
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


    buildableContext.recordArtifact(getAndroidManifestXml());
    buildableContext.recordArtifact(getResourceApkPath());

    steps.add(new RecordFileSha1Step(
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
    steps.add(new MakeCleanDirectoryStep(rDotJavaSrc));

    Path rDotTxtDir = getPathToRDotTxtDir();
    MergeAndroidResourcesStep mergeStep = MergeAndroidResourcesStep.createStepForUberRDotJava(
        resourceDeps,
        rDotTxtDir.resolve("R.txt"),
        shouldWarnIfMissingResource,
        rDotJavaSrc);
    steps.add(mergeStep);

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      steps.add(new MakeCleanDirectoryStep(outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo = new GenStringSourceMapStep(
          rDotTxtDir,
          filteredResourcesProvider.getResDirectories(),
          outputDirPath);
      steps.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifactsInDirectory(outputDirPath);
    }

    // Create the path where the R.java files will be compiled.
    Path rDotJavaBin = getPathToCompiledRDotJavaFiles();
    steps.add(new MakeCleanDirectoryStep(rDotJavaBin));

    JavacStep javacStep = RDotJava.createJavacStepForUberRDotJavaFiles(
        ImmutableSet.copyOf(getResolver().getAllPaths(mergeStep.getRDotJavaFiles())),
        rDotJavaBin,
        javacOptions,
        getBuildTarget());
    steps.add(javacStep);

    Path rDotJavaClassesTxt = getPathToRDotJavaClassesTxt();
    steps.add(new MakeCleanDirectoryStep(rDotJavaClassesTxt.getParent()));
    steps.add(new AccumulateClassNamesStep(Optional.of(rDotJavaBin), rDotJavaClassesTxt));

    // Ensure the generated R.txt, R.java, and R.class files are also recorded.
    buildableContext.recordArtifactsInDirectory(rDotTxtDir);
    buildableContext.recordArtifactsInDirectory(rDotJavaSrc);
    buildableContext.recordArtifactsInDirectory(rDotJavaBin);
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
    return BuildTargets.getBinPath(getBuildTarget(), "__manifest_%s__/AndroidManifest.xml");
  }

  /**
   * Given a set of assets directories to include in the APK (which may be empty), return the path
   * to the directory that contains the union of all the assets. If any work needs to be done to
   * create such a directory, the appropriate commands should be added to the {@code commands}
   * list builder.
   * <p>
   * If there are no assets (i.e., {@code assetsDirectories} is empty), then the return value will
   * be an empty {@link Optional}.
   */
  @VisibleForTesting
  Optional<Path> createAllAssetsDirectory(
      Set<Path> assetsDirectories,
      ImmutableList.Builder<Step> steps,
      ProjectFilesystem filesystem) throws IOException {
    if (assetsDirectories.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    Path destination = getPathToAllAssetsDirectory();
    steps.add(new MakeCleanDirectoryStep(destination));
    final ImmutableMap.Builder<Path, Path> allAssets = ImmutableMap.builder();

    for (final Path assetsDirectory : assetsDirectories) {
      if (!filesystem.exists(assetsDirectory)) {
        continue;
      }
      filesystem.walkRelativeFileTree(
          assetsDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!AaptStep.isSilentlyIgnored(file)) {
                allAssets.put(assetsDirectory.relativize(file), file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }

    for (Map.Entry<Path, Path> entry : allAssets.build().entrySet()) {
      steps.add(new MkdirAndSymlinkFileStep(
          entry.getValue(),
          destination.resolve(entry.getKey())));
    }

    return Optional.of(destination);
  }

  /**
   * @return Path to the unsigned APK generated by this {@link com.facebook.buck.rules.BuildRule}.
   */
  public Path getResourceApkPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.unsigned.ap_");
  }

  @VisibleForTesting
  Path getPathToAllAssetsDirectory() {
    return BuildTargets.getBinPath(getBuildTarget(), "__assets_%s__");
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
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> resourcePackageHash = onDiskBuildInfo.getHash(RESOURCE_PACKAGE_HASH_KEY);
    Preconditions.checkState(
        resourcePackageHash.isPresent(),
        "Should not be initializing %s from disk if the resource hash is not written.",
        getBuildTarget());

    ImmutableSortedMap<String, HashCode> classesHash = ImmutableSortedMap.of();
    if (!filteredResourcesProvider.getResDirectories().isEmpty()) {
      List<String> lines;
      try {
        lines = onDiskBuildInfo.getOutputFileContentsByLine(getPathToRDotJavaClassesTxt());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
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
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_rdotjava_dex__");
  }

  private Path getPathToRDotJavaClassesTxt() {
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_rdotjava_classes__")
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
    return BuildTargets.getBinPath(getBuildTarget(), "__%s_string_source_map__");
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
    return BuildTargets.getBinPath(buildTarget, "__%s_rdotjava_src__");
  }

  @VisibleForTesting
  FilteredResourcesProvider getFilteredResourcesProvider() {
    return filteredResourcesProvider;
  }
}
