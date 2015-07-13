/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.android.PreDexMerge.BuildOutput;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
/**
 * Buildable that is responsible for:
 * <ul>
 *   <li>Bucketing pre-dexed jars into lists for primary and secondary dex files
 *       (if the app is split-dex).
 *   <li>Merging the pre-dexed jars into primary and secondary dex files.
 *   <li>Writing the split-dex "metadata.txt".
 * </ul>
 * <p>
 * Clients of this Buildable may need to know:
 * <ul>
 *   <li>The locations of the zip files directories containing secondary dex files and metadata.
 * </ul>
 *
 * This uses a separate implementation from addDexingSteps.
 * The differences in the splitting logic are too significant to make it
 * worth merging them.
 */
public class PreDexMerge extends AbstractBuildRule implements InitializableFromDisk<BuildOutput> {

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  private static final EnumSet<DxStep.Option> DX_MERGE_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.RUN_IN_PROCESS,
      DxStep.Option.NO_OPTIMIZE);

  private static final String PRIMARY_DEX_HASH_KEY = "primary_dex_hash";
  private static final String SECONDARY_DEX_DIRECTORIES_KEY = "secondary_dex_directories";

  private final Path primaryDexPath;
  @AddToRuleKey
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<DexProducedFromJavaLibrary> preDexDeps;
  private final AaptPackageResources aaptPackageResources;
  private final ListeningExecutorService dxExecutorService;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;
  private final Optional<Integer> xzCompressionLevel;

  public PreDexMerge(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<DexProducedFromJavaLibrary> preDexDeps,
      AaptPackageResources aaptPackageResources,
      ListeningExecutorService dxExecutorService,
      Optional<Integer> xzCompressionLevel) {
    super(params, resolver);
    this.primaryDexPath = primaryDexPath;
    this.dexSplitMode = dexSplitMode;
    this.preDexDeps = preDexDeps;
    this.aaptPackageResources = aaptPackageResources;
    this.dxExecutorService = dxExecutorService;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.xzCompressionLevel = xzCompressionLevel;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MkdirStep(primaryDexPath.getParent()));

    if (dexSplitMode.isShouldSplitDex()) {
      addStepsForSplitDex(steps, context, buildableContext);
    } else {
      addStepsForSingleDex(steps, buildableContext);
    }
    return steps.build();
  }

  /**
   * Wrapper class for all the paths we need when merging for a split-dex APK.
   */
  private final class SplitDexPaths {
    private final Path metadataDir;
    private final Path jarfilesDir;
    private final Path scratchDir;
    private final Path successDir;
    private final Path metadataSubdir;
    private final Path jarfilesSubdir;
    private final Path metadataFile;

    private SplitDexPaths() {
      Path workDir = BuildTargets.getScratchPath(getBuildTarget(), "_%s_output");

      metadataDir = workDir.resolve("metadata");
      jarfilesDir = workDir.resolve("jarfiles");
      scratchDir = workDir.resolve("scratch");
      successDir = workDir.resolve("success");
      // These directories must use SECONDARY_DEX_SUBDIR because that mirrors the paths that
      // they will appear at in the APK.
      metadataSubdir = metadataDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      jarfilesSubdir = jarfilesDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      metadataFile = metadataSubdir.resolve("metadata.txt");
    }
  }

  private void addStepsForSplitDex(
      ImmutableList.Builder<Step> steps,
      BuildContext context,
      BuildableContext buildableContext) {

    // Collect all of the DexWithClasses objects to use for merging.
    ImmutableList<DexWithClasses> dexFilesToMerge = FluentIterable.from(preDexDeps)
        .transform(DexWithClasses.TO_DEX_WITH_CLASSES)
        .filter(Predicates.notNull())
        .toList();

    final SplitDexPaths paths = new SplitDexPaths();

    final ImmutableSet<Path> secondaryDexDirectories;
    if (dexSplitMode.getDexStore() == DexStore.RAW) {
      // Raw classes*.dex files go in the top-level of the APK.
      secondaryDexDirectories = ImmutableSet.of(paths.jarfilesSubdir
      );
    } else {
      // Otherwise, we want to include the metadata and jars as assets.
      secondaryDexDirectories = ImmutableSet.of(paths.metadataDir, paths.jarfilesDir);
    }
    // Do not clear existing directory which might contain secondary dex files that are not
    // re-merged (since their contents did not change).
    steps.add(new MkdirStep(paths.jarfilesSubdir));
    steps.add(new MkdirStep(paths.successDir));

    steps.add(new MakeCleanDirectoryStep(paths.metadataSubdir));
    steps.add(new MakeCleanDirectoryStep(paths.scratchDir));

    buildableContext.addMetadata(
        SECONDARY_DEX_DIRECTORIES_KEY,
        Iterables.transform(secondaryDexDirectories, Functions.toStringFunction()));

    buildableContext.recordArtifact(primaryDexPath);
    buildableContext.recordArtifact(paths.jarfilesSubdir);
    buildableContext.recordArtifact(paths.metadataSubdir);
    buildableContext.recordArtifact(paths.successDir);

    PreDexedFilesSorter preDexedFilesSorter = new PreDexedFilesSorter(
        aaptPackageResources.getRDotJavaDexWithClasses(),
        dexFilesToMerge,
        dexSplitMode.getPrimaryDexPatterns(),
        paths.scratchDir,
        dexSplitMode.getLinearAllocHardLimit(),
        dexSplitMode.getDexStore(),
        paths.jarfilesSubdir);
    final PreDexedFilesSorter.Result sortResult =
        preDexedFilesSorter.sortIntoPrimaryAndSecondaryDexes(context, steps);

    steps.add(new SmartDexingStep(
        primaryDexPath,
        Suppliers.ofInstance(sortResult.primaryDexInputs),
        Optional.of(paths.jarfilesSubdir),
        Optional.of(Suppliers.ofInstance(sortResult.secondaryOutputToInputs)),
        sortResult.dexInputHashesProvider,
        paths.successDir,
        DX_MERGE_OPTIONS,
        dxExecutorService,
        xzCompressionLevel));

    // Record the primary dex SHA1 so exopackage apks can use it to compute their ABI keys.
    // Single dex apks cannot be exopackages, so they will never need ABI keys.
    steps.add(new RecordFileSha1Step(
        primaryDexPath,
        PRIMARY_DEX_HASH_KEY,
        buildableContext));

    steps.add(new AbstractExecutionStep("write_metadata_txt") {
      @Override
      public int execute(ExecutionContext executionContext) {
        ProjectFilesystem filesystem = executionContext.getProjectFilesystem();
        Map<Path, DexWithClasses> metadataTxtEntries = sortResult.metadataTxtDexEntries;
        List<String> lines = Lists.newArrayListWithCapacity(metadataTxtEntries.size());
        if (dexSplitMode.getDexStore() == DexStore.RAW) {
          lines.add(".root_relative");
        }

        try {
          for (Map.Entry<Path, DexWithClasses> entry : metadataTxtEntries.entrySet()) {
            Path pathToSecondaryDex = entry.getKey();
            String containedClass = Iterables.get(entry.getValue().getClassNames(), 0);
            containedClass = containedClass.replace('/', '.');
            String hash = filesystem.computeSha1(pathToSecondaryDex);
            lines.add(String.format("%s %s %s",
                pathToSecondaryDex.getFileName(), hash, containedClass));
          }
          filesystem.writeLinesToPath(lines, paths.metadataFile);
        } catch (IOException e) {
          executionContext.logError(e, "Failed when writing metadata.txt multi-dex.");
          return 1;
        }
        return 0;
      }
    });
  }

  private void addStepsForSingleDex(
      ImmutableList.Builder<Step> steps,
      final BuildableContext buildableContext) {
    // For single-dex apps with pre-dexing, we just add the steps directly.
    Iterable<Path> filesToDex = FluentIterable.from(preDexDeps)
        .transform(
            new Function<DexProducedFromJavaLibrary, Path>() {
              @Override
              @Nullable
              public Path apply(DexProducedFromJavaLibrary preDex) {
                if (preDex.hasOutput()) {
                  return preDex.getPathToDex();
                } else {
                  return null;
                }
              }
            })
        .filter(Predicates.notNull());

    // If this APK has Android resources, then the generated R.class files also need to be dexed.
    Optional<DexWithClasses> rDotJavaDexWithClasses =
        aaptPackageResources.getRDotJavaDexWithClasses();
    if (rDotJavaDexWithClasses.isPresent()) {
      filesToDex = Iterables.concat(
          filesToDex,
          Collections.singleton(rDotJavaDexWithClasses.get().getPathToDexFile()));
    }

    buildableContext.recordArtifact(primaryDexPath);

    // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
    steps.add(new DxStep(primaryDexPath, filesToDex, DX_MERGE_OPTIONS));

    buildableContext.addMetadata(
        SECONDARY_DEX_DIRECTORIES_KEY,
        ImmutableSet.<String>of());
  }

  public Path getMetadataTxtPath() {
    Preconditions.checkState(dexSplitMode.isShouldSplitDex());
    return new SplitDexPaths().metadataFile;
  }

  public Path getDexDirectory() {
    Preconditions.checkState(dexSplitMode.isShouldSplitDex());
    return new SplitDexPaths().jarfilesSubdir;
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

  @Nullable
  public Sha1HashCode getPrimaryDexHash() {
    Preconditions.checkState(dexSplitMode.isShouldSplitDex());
    return buildOutputInitializer.getBuildOutput().primaryDexHash;
  }

  public ImmutableSet<Path> getSecondaryDexDirectories() {
    return buildOutputInitializer.getBuildOutput().secondaryDexDirectories;
  }

  static class BuildOutput {
    /** Null iff this is a single-dex app. */
    @Nullable private final Sha1HashCode primaryDexHash;
    private final ImmutableSet<Path> secondaryDexDirectories;

    BuildOutput(@Nullable Sha1HashCode primaryDexHash, ImmutableSet<Path> secondaryDexDirectories) {
      this.primaryDexHash = primaryDexHash;
      this.secondaryDexDirectories = secondaryDexDirectories;
    }
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> primaryDexHash = onDiskBuildInfo.getHash(PRIMARY_DEX_HASH_KEY);
    // We only save the hash for split-dex builds.
    if (dexSplitMode.isShouldSplitDex()) {
      Preconditions.checkState(primaryDexHash.isPresent());
    }

    return new BuildOutput(
        primaryDexHash.orNull(),
        FluentIterable.from(onDiskBuildInfo.getValues(SECONDARY_DEX_DIRECTORIES_KEY).get())
            .transform(MorePaths.TO_PATH)
            .toSet());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
