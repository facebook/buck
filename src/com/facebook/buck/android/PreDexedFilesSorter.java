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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.CanaryFactory;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** Responsible for bucketing pre-dexed objects into primary and secondary dex files. */
public class PreDexedFilesSorter {
  private final Collection<DexWithClasses> dexFilesToMerge;
  private final ClassNameFilter primaryDexFilter;
  private final APKModule module;
  private final APKModuleGraph apkModuleGraph;
  private final long dexWeightLimit;
  private final DexStore dexStore;
  private final Path secondaryDexJarFilesDir;
  private final Optional<Integer> groupIndex;

  /**
   * Directory under the project filesystem where this step may write temporary data. This directory
   * must exist and be empty before this step writes to it.
   */
  private final Path canaryDirectory;

  public PreDexedFilesSorter(
      Collection<DexWithClasses> dexFilesToMerge,
      ImmutableSet<String> primaryDexPatterns,
      APKModuleGraph apkModuleGraph,
      APKModule module,
      Path canaryDirectory,
      long dexWeightLimit,
      DexStore dexStore,
      Path secondaryDexJarFilesDir,
      Optional<Integer> groupIndex) {
    this.dexFilesToMerge = dexFilesToMerge;
    this.primaryDexFilter = ClassNameFilter.fromConfiguration(primaryDexPatterns);
    this.apkModuleGraph = apkModuleGraph;
    this.module = module;
    this.canaryDirectory = canaryDirectory;
    Preconditions.checkState(dexWeightLimit > 0);
    this.dexWeightLimit = dexWeightLimit;
    this.dexStore = dexStore;
    this.secondaryDexJarFilesDir = secondaryDexJarFilesDir;
    this.groupIndex = groupIndex;
  }

  /**
   * Sorts dex files into primary and secondary dexes, generate canary classes, and metadata for use
   * by SmartDexingStep
   */
  public Result sortIntoPrimaryAndSecondaryDexes(
      ProjectFilesystem filesystem, ImmutableList.Builder<Step> steps) {
    DexStoreContents storeContents = new DexStoreContents(filesystem, steps);

    // Sort dex files so that there's a better chance of the same set of pre-dexed files to end up
    // in a given secondary dex file.
    ImmutableList<DexWithClasses> sortedDexFilesToMerge =
        dexFilesToMerge.stream()
            .sorted(DexWithClasses.DEX_WITH_CLASSES_COMPARATOR)
            .collect(ImmutableList.toImmutableList());

    // Bucket each DexWithClasses into the appropriate dex file.
    for (DexWithClasses dexWithClasses : sortedDexFilesToMerge) {
      if (module.equals(apkModuleGraph.getRootAPKModule()) && mustBeInPrimaryDex(dexWithClasses)) {
        // Case 1: Entry must be in the primary dex.
        storeContents.addPrimaryDex(dexWithClasses);
      } else {
        storeContents.addDex(dexWithClasses);
      }
    }
    return storeContents.getResult();
  }

  private boolean mustBeInPrimaryDex(DexWithClasses dexWithClasses) {
    for (String className : dexWithClasses.getClassNames()) {
      if (primaryDexFilter.matches(className)) {
        return true;
      }
    }
    return false;
  }

  public class DexStoreContents {
    private List<List<DexWithClasses>> secondaryDexesContents = new ArrayList<>();
    private int primaryDexSize;
    private List<DexWithClasses> primaryDexContents;
    private int currentSecondaryDexSize;
    private List<DexWithClasses> currentSecondaryDexContents;

    private final ProjectFilesystem filesystem;
    private final ImmutableList.Builder<Step> steps;
    private final ImmutableMap.Builder<String, DexMetadata> primaryDexInputMetadata =
        ImmutableMap.builder();
    private final ImmutableMap.Builder<SourcePath, Sha1HashCode> secondaryDexInputsHashes =
        ImmutableMap.builder();

    public DexStoreContents(ProjectFilesystem filesystem, ImmutableList.Builder<Step> steps) {
      this.filesystem = filesystem;
      this.steps = steps;
      currentSecondaryDexSize = 0;
      currentSecondaryDexContents = new ArrayList<>();
      primaryDexSize = 0;
      primaryDexContents = new ArrayList<>();
    }

    public void addPrimaryDex(DexWithClasses dexWithClasses) {
      primaryDexSize += dexWithClasses.getWeightEstimate();
      primaryDexContents.add(dexWithClasses);
      primaryDexInputMetadata.put(
          getJarName(dexWithClasses),
          ImmutableDexMetadata.of(
              dexWithClasses.getWeightEstimate(), dexWithClasses.getClassesHash().toString()));
    }

    private String getJarName(DexWithClasses dexWithClasses) {
      BuildTarget target = dexWithClasses.getSourceBuildTarget();
      Preconditions.checkState(target != null, "Jar name only valid for predexed libraries");
      return target.getFullyQualifiedName().replaceAll("[/:]", "_") + "_dex.jar";
    }

    public void addDex(DexWithClasses dexWithClasses) {
      // If we're over the size threshold, start writing to a new dex
      if (dexWithClasses.getWeightEstimate() + currentSecondaryDexSize > dexWeightLimit) {
        currentSecondaryDexSize = 0;
        currentSecondaryDexContents = new ArrayList<>();
      }

      // If this is the first class in the dex, initialize it with a canary and add it to the set of
      // dexes.
      if (currentSecondaryDexContents.isEmpty()) {
        DexWithClasses canary =
            createCanary(
                filesystem, module.getCanaryClassName(), secondaryDexesContents.size() + 1, steps);
        currentSecondaryDexSize += canary.getWeightEstimate();
        currentSecondaryDexContents.add(canary);

        secondaryDexesContents.add(currentSecondaryDexContents);
        secondaryDexInputsHashes.put(canary.getSourcePathToDexFile(), canary.getClassesHash());
      }

      // Now add the contributions from the dexWithClasses entry.
      currentSecondaryDexContents.add(dexWithClasses);
      secondaryDexInputsHashes.put(
          dexWithClasses.getSourcePathToDexFile(), dexWithClasses.getClassesHash());
      currentSecondaryDexSize += dexWithClasses.getWeightEstimate();
    }

    Result getResult() {
      ImmutableSortedMap.Builder<Path, DexWithClasses> metadataTxtEntries =
          ImmutableSortedMap.naturalOrder();
      ImmutableMultimap.Builder<Path, SourcePath> secondaryOutputToInputs =
          ImmutableMultimap.builder();
      secondaryOutputToInputs.orderKeysBy(Ordering.natural());

      for (int index = 0; index < secondaryDexesContents.size(); index++) {
        String filename = dexStore.fileNameForSecondary(module, dexStore.index(groupIndex, index));
        Path pathToSecondaryDex = secondaryDexJarFilesDir.resolve(filename);
        metadataTxtEntries.put(pathToSecondaryDex, secondaryDexesContents.get(index).get(0));
        Collection<SourcePath> dexContentPaths =
            Collections2.transform(
                secondaryDexesContents.get(index), DexWithClasses::getSourcePathToDexFile);
        secondaryOutputToInputs.putAll(pathToSecondaryDex, dexContentPaths);
      }

      ImmutableMap.Builder<String, SourcePath> builder = ImmutableMap.builder();
      primaryDexContents.forEach(dex -> builder.put(getJarName(dex), dex.getSourcePathToDexFile()));

      return new Result(
          builder.build(),
          secondaryOutputToInputs.build(),
          metadataTxtEntries.build(),
          ImmutablePrimaryDexInputMetadata.of(primaryDexSize, primaryDexInputMetadata.build()),
          secondaryDexInputsHashes.build());
    }

    /** @see CanaryFactory#create(String, String) */
    private DexWithClasses createCanary(
        ProjectFilesystem filesystem,
        String storeName,
        int index,
        ImmutableList.Builder<Step> steps) {
      String canaryIndex = dexStore.index(groupIndex, index);
      if (!groupIndex.isPresent()) {
        canaryIndex = String.format("%02d", index);
      }
      FileLike fileLike = CanaryFactory.create(storeName, canaryIndex);
      String canaryDirName = String.format("canary_%s_%d", storeName, index);
      Path scratchDirectoryForCanaryClass = canaryDirectory.resolve(canaryDirName);

      // Strip the .class suffix to get the class name for the DexWithClasses object.
      String relativePathToClassFile = fileLike.getRelativePath();
      Preconditions.checkState(relativePathToClassFile.endsWith(".class"));
      String className = relativePathToClassFile.replaceFirst("\\.class$", "");

      // Write out the .class file.
      steps.add(
          new AbstractExecutionStep("write_canary_class") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              Path classFile = scratchDirectoryForCanaryClass.resolve(relativePathToClassFile);
              try (InputStream inputStream = fileLike.getInput()) {
                filesystem.createParentDirs(classFile);
                filesystem.copyToPath(inputStream, classFile);
              }
              return StepExecutionResults.SUCCESS;
            }
          });

      return new DexWithClasses() {
        @Override
        public int getWeightEstimate() {
          // Because we do not know the units being used for DEX size estimation and the canary
          // should be very small, assume the size is zero.
          return 0;
        }

        @Nullable
        @Override
        public BuildTarget getSourceBuildTarget() {
          return null;
        }

        @Override
        public SourcePath getSourcePathToDexFile() {
          return PathSourcePath.of(filesystem, scratchDirectoryForCanaryClass);
        }

        @Override
        public ImmutableSet<String> getClassNames() {
          return ImmutableSet.of(className);
        }

        @Override
        public Sha1HashCode getClassesHash() {
          // The only thing unique to canary classes is the index,
          // which is captured by canaryDirName.
          Hasher hasher = Hashing.sha1().newHasher();
          hasher.putString(canaryDirName, Charsets.UTF_8);
          return Sha1HashCode.fromHashCode(hasher.hash());
        }
      };
    }
  }

  /** A serializable subset of DexWithClasses used when merging primary dexes */
  @BuckStyleValue
  @JsonSerialize
  @JsonDeserialize(as = ImmutableDexMetadata.class)
  abstract static class DexMetadata {
    public abstract int getWeight();

    public abstract String getHash();
  }

  /** Serializable metadata for the contribution to the primary dex of a single dex group */
  @BuckStyleValue
  @JsonSerialize
  @JsonDeserialize(as = ImmutablePrimaryDexInputMetadata.class)
  abstract static class PrimaryDexInputMetadata {
    public abstract int getWeight();

    public abstract ImmutableMap<String, PreDexedFilesSorter.DexMetadata> getMetadata();
  }

  public static class Result {
    public final Map<String, SourcePath> primaryDexInputs;
    public final Multimap<Path, SourcePath> secondaryOutputToInputs;
    public final Map<Path, DexWithClasses> metadataTxtDexEntries;
    public final ImmutablePrimaryDexInputMetadata primaryDexInputMetadata;
    public final ImmutableMap<SourcePath, Sha1HashCode> secondaryDexInputHashes;

    public Result(
        Map<String, SourcePath> primaryDexInputs,
        Multimap<Path, SourcePath> secondaryOutputToInputs,
        Map<Path, DexWithClasses> metadataTxtDexEntries,
        ImmutablePrimaryDexInputMetadata primaryDexInputMetadata,
        ImmutableMap<SourcePath, Sha1HashCode> secondaryDexInputHashes) {
      this.primaryDexInputs = primaryDexInputs;
      this.secondaryOutputToInputs = secondaryOutputToInputs;
      this.metadataTxtDexEntries = metadataTxtDexEntries;
      this.primaryDexInputMetadata = primaryDexInputMetadata;
      this.secondaryDexInputHashes = secondaryDexInputHashes;
    }
  }
}
