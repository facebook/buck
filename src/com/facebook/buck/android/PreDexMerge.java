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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
/**
 * Buildable that is responsible for:
 *
 * <ul>
 *   <li>Bucketing pre-dexed jars into lists for primary and secondary dex files (if the app is
 *       split-dex).
 *   <li>Merging the pre-dexed jars into primary and secondary dex files.
 *   <li>Writing the split-dex "metadata.txt".
 * </ul>
 *
 * <p>Clients of this Buildable may need to know:
 *
 * <ul>
 *   <li>The locations of the zip files directories containing secondary dex files and metadata.
 * </ul>
 *
 * This uses a separate implementation from addDexingSteps. The differences in the splitting logic
 * are too significant to make it worth merging them.
 */
public class PreDexMerge extends AbstractBuildRule implements InitializableFromDisk<BuildOutput> {

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  private static final EnumSet<DxStep.Option> DX_MERGE_OPTIONS =
      EnumSet.of(
          DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
          DxStep.Option.RUN_IN_PROCESS,
          DxStep.Option.NO_OPTIMIZE);

  private static final String PRIMARY_DEX_HASH_KEY = "primary_dex_hash";
  private static final String SECONDARY_DEX_DIRECTORIES_KEY = "secondary_dex_directories";

  private final Path primaryDexPath;
  @AddToRuleKey private final DexSplitMode dexSplitMode;
  private final APKModuleGraph apkModuleGraph;
  private final ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexDeps;
  private final DexProducedFromJavaLibrary dexForUberRDotJava;
  private final ListeningExecutorService dxExecutorService;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;
  private final Optional<Integer> xzCompressionLevel;
  private final Optional<String> dxMaxHeapSize;

  public PreDexMerge(
      BuildRuleParams params,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      APKModuleGraph apkModuleGraph,
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexDeps,
      DexProducedFromJavaLibrary dexForUberRDotJava,
      ListeningExecutorService dxExecutorService,
      Optional<Integer> xzCompressionLevel,
      Optional<String> dxMaxHeapSize) {
    super(params);
    this.primaryDexPath = primaryDexPath;
    this.dexSplitMode = dexSplitMode;
    this.apkModuleGraph = apkModuleGraph;
    this.preDexDeps = preDexDeps;
    this.dexForUberRDotJava = dexForUberRDotJava;
    this.dxExecutorService = dxExecutorService;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
    this.xzCompressionLevel = xzCompressionLevel;
    this.dxMaxHeapSize = dxMaxHeapSize;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(MkdirStep.of(getProjectFilesystem(), primaryDexPath.getParent()));

    if (dexSplitMode.isShouldSplitDex()) {
      addStepsForSplitDex(steps, buildableContext);
    } else {
      addStepsForSingleDex(steps, buildableContext);
    }
    return steps.build();
  }

  /** Wrapper class for all the paths we need when merging for a split-dex APK. */
  private final class SplitDexPaths {
    private final Path metadataDir;
    private final Path jarfilesDir;
    private final Path scratchDir;
    private final Path successDir;
    private final Path metadataSubdir;
    private final Path jarfilesSubdir;
    private final Path additionalJarfilesDir;
    private final Path additionalJarfilesSubdir;
    private final Path metadataFile;

    private SplitDexPaths() {
      Path workDir =
          BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "_%s_output");

      metadataDir = workDir.resolve("metadata");
      jarfilesDir = workDir.resolve("jarfiles");
      scratchDir = workDir.resolve("scratch");
      successDir = workDir.resolve("success");
      // These directories must use SECONDARY_DEX_SUBDIR because that mirrors the paths that
      // they will appear at in the APK.
      metadataSubdir = metadataDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      jarfilesSubdir = jarfilesDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);
      additionalJarfilesDir = workDir.resolve("additional_dexes");
      additionalJarfilesSubdir = additionalJarfilesDir.resolve("assets");
      metadataFile = metadataSubdir.resolve("metadata.txt");
    }
  }

  private void addStepsForSplitDex(
      ImmutableList.Builder<Step> steps, BuildableContext buildableContext) {

    // Collect all of the DexWithClasses objects to use for merging.
    ImmutableMultimap.Builder<APKModule, DexWithClasses> dexFilesToMergeBuilder =
        ImmutableMultimap.builder();
    dexFilesToMergeBuilder.putAll(
        FluentIterable.from(preDexDeps.entries())
            .transform(
                input ->
                    new AbstractMap.SimpleEntry<>(
                        input.getKey(), DexWithClasses.TO_DEX_WITH_CLASSES.apply(input.getValue())))
            .filter(input -> input.getValue() != null)
            .toSet());

    final SplitDexPaths paths = new SplitDexPaths();

    final ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    if (dexSplitMode.getDexStore() == DexStore.RAW) {
      // Raw classes*.dex files go in the top-level of the APK.
      secondaryDexDirectories.add(paths.jarfilesSubdir);
    } else {
      // Otherwise, we want to include the metadata and jars as assets.
      secondaryDexDirectories.add(paths.metadataDir);
      secondaryDexDirectories.add(paths.jarfilesDir);
    }
    //always add additional dex stores and metadata as assets
    secondaryDexDirectories.add(paths.additionalJarfilesDir);

    // Do not clear existing directory which might contain secondary dex files that are not
    // re-merged (since their contents did not change).
    steps.add(MkdirStep.of(getProjectFilesystem(), paths.jarfilesSubdir));
    steps.add(MkdirStep.of(getProjectFilesystem(), paths.additionalJarfilesSubdir));
    steps.add(MkdirStep.of(getProjectFilesystem(), paths.successDir));

    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), paths.metadataSubdir));
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), paths.scratchDir));

    buildableContext.addMetadata(
        SECONDARY_DEX_DIRECTORIES_KEY,
        secondaryDexDirectories
            .build()
            .stream()
            .map(Object::toString)
            .collect(MoreCollectors.toImmutableList()));

    buildableContext.recordArtifact(primaryDexPath);
    buildableContext.recordArtifact(paths.jarfilesSubdir);
    buildableContext.recordArtifact(paths.metadataSubdir);
    buildableContext.recordArtifact(paths.successDir);
    buildableContext.recordArtifact(paths.additionalJarfilesSubdir);

    PreDexedFilesSorter preDexedFilesSorter =
        new PreDexedFilesSorter(
            Optional.ofNullable(DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexForUberRDotJava)),
            dexFilesToMergeBuilder.build(),
            dexSplitMode.getPrimaryDexPatterns(),
            apkModuleGraph,
            paths.scratchDir,
            // We kind of overload the "getLinearAllocHardLimit" parameter
            // to set the dex weight limit during pre-dex merging.
            dexSplitMode.getLinearAllocHardLimit(),
            dexSplitMode.getDexStore(),
            paths.jarfilesSubdir,
            paths.additionalJarfilesSubdir);
    final ImmutableMap<String, PreDexedFilesSorter.Result> sortResults =
        preDexedFilesSorter.sortIntoPrimaryAndSecondaryDexes(getProjectFilesystem(), steps);

    PreDexedFilesSorter.Result rootApkModuleResult =
        sortResults.get(APKModuleGraph.ROOT_APKMODULE_NAME);
    if (rootApkModuleResult == null) {
      throw new HumanReadableException("No classes found in primary or secondary dexes");
    }

    Multimap<Path, Path> aggregatedOutputToInputs = HashMultimap.create();
    ImmutableMap.Builder<Path, Sha1HashCode> dexInputHashesBuilder = ImmutableMap.builder();
    for (PreDexedFilesSorter.Result result : sortResults.values()) {
      if (!result.apkModule.equals(apkModuleGraph.getRootAPKModule())) {
        Path dexOutputPath = paths.additionalJarfilesSubdir.resolve(result.apkModule.getName());
        steps.add(MkdirStep.of(getProjectFilesystem(), dexOutputPath));
      }
      aggregatedOutputToInputs.putAll(result.secondaryOutputToInputs);
      dexInputHashesBuilder.putAll(result.dexInputHashes);
    }
    final ImmutableMap<Path, Sha1HashCode> dexInputHashes = dexInputHashesBuilder.build();

    steps.add(
        new SmartDexingStep(
            getProjectFilesystem(),
            primaryDexPath,
            Suppliers.ofInstance(rootApkModuleResult.primaryDexInputs),
            Optional.of(paths.jarfilesSubdir),
            Optional.of(Suppliers.ofInstance(aggregatedOutputToInputs)),
            () -> dexInputHashes,
            paths.successDir,
            DX_MERGE_OPTIONS,
            dxExecutorService,
            xzCompressionLevel,
            dxMaxHeapSize));

    // Record the primary dex SHA1 so exopackage apks can use it to compute their ABI keys.
    // Single dex apks cannot be exopackages, so they will never need ABI keys.
    steps.add(
        new RecordFileSha1Step(
            getProjectFilesystem(), primaryDexPath, PRIMARY_DEX_HASH_KEY, buildableContext));

    for (PreDexedFilesSorter.Result result : sortResults.values()) {
      if (!result.apkModule.equals(apkModuleGraph.getRootAPKModule())) {
        Path dexMetadataOutputPath =
            paths
                .additionalJarfilesSubdir
                .resolve(result.apkModule.getName())
                .resolve("metadata.txt");

        addMetadataWriteStep(result, steps, dexMetadataOutputPath);
      }
    }

    addMetadataWriteStep(rootApkModuleResult, steps, paths.metadataFile);
  }

  private void addMetadataWriteStep(
      final PreDexedFilesSorter.Result result,
      final ImmutableList.Builder<Step> steps,
      final Path metadataFilePath) {
    StringBuilder nameBuilder = new StringBuilder(30);
    final boolean isRootModule = result.apkModule.equals(apkModuleGraph.getRootAPKModule());
    final String storeId = result.apkModule.getName();
    nameBuilder.append("write_");
    if (!isRootModule) {
      nameBuilder.append(storeId);
      nameBuilder.append("_");
    }
    nameBuilder.append("metadata_txt");
    steps.add(
        new AbstractExecutionStep(nameBuilder.toString()) {
          @Override
          public StepExecutionResult execute(ExecutionContext executionContext) {
            Map<Path, DexWithClasses> metadataTxtEntries = result.metadataTxtDexEntries;
            List<String> lines = Lists.newArrayListWithCapacity(metadataTxtEntries.size());

            lines.add(".id " + storeId);
            if (isRootModule) {
              if (dexSplitMode.getDexStore() == DexStore.RAW) {
                lines.add(".root_relative");
              }
            } else {
              for (APKModule dependency :
                  apkModuleGraph.getGraph().getOutgoingNodesFor(result.apkModule)) {
                lines.add(".requires " + dependency.getName());
              }
            }

            try {
              for (Map.Entry<Path, DexWithClasses> entry : metadataTxtEntries.entrySet()) {
                Path pathToSecondaryDex = entry.getKey();
                String containedClass = Iterables.get(entry.getValue().getClassNames(), 0);
                containedClass = containedClass.replace('/', '.');
                Sha1HashCode hash = getProjectFilesystem().computeSha1(pathToSecondaryDex);
                lines.add(
                    String.format(
                        "%s %s %s", pathToSecondaryDex.getFileName(), hash, containedClass));
              }
              getProjectFilesystem().writeLinesToPath(lines, metadataFilePath);
            } catch (IOException e) {
              executionContext.logError(e, "Failed when writing metadata.txt multi-dex.");
              return StepExecutionResult.ERROR;
            }
            return StepExecutionResult.SUCCESS;
          }
        });
  }

  private void addStepsForSingleDex(
      ImmutableList.Builder<Step> steps, final BuildableContext buildableContext) {
    // For single-dex apps with pre-dexing, we just add the steps directly.
    Iterable<Path> filesToDex =
        FluentIterable.from(preDexDeps.values())
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
            .filter(Objects::nonNull);

    // If this APK has Android resources, then the generated R.class files also need to be dexed.
    Optional<DexWithClasses> rDotJavaDexWithClasses =
        Optional.ofNullable(DexWithClasses.TO_DEX_WITH_CLASSES.apply(dexForUberRDotJava));
    if (rDotJavaDexWithClasses.isPresent()) {
      filesToDex =
          Iterables.concat(
              filesToDex, Collections.singleton(rDotJavaDexWithClasses.get().getPathToDexFile()));
    }

    buildableContext.recordArtifact(primaryDexPath);

    // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
    steps.add(new DxStep(getProjectFilesystem(), primaryDexPath, filesToDex, DX_MERGE_OPTIONS));

    buildableContext.addMetadata(SECONDARY_DEX_DIRECTORIES_KEY, ImmutableList.of());
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
  public SourcePath getSourcePathToOutput() {
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
        primaryDexHash.orElse(null),
        onDiskBuildInfo
            .getValues(SECONDARY_DEX_DIRECTORIES_KEY)
            .get()
            .stream()
            .map(Paths::get)
            .collect(MoreCollectors.toImmutableSet()));
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
