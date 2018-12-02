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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.dalvik.CanaryFactory;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Responsible for bucketing pre-dexed objects into primary and secondary dex files. */
public class PreDexedFilesSorter {

  private final ImmutableMultimap<APKModule, DexWithClasses> dexFilesToMerge;
  private final ClassNameFilter primaryDexFilter;
  private final APKModuleGraph apkModuleGraph;
  private final long dexWeightLimit;
  private final DexStore dexStore;
  private final Path secondaryDexJarFilesDir;
  private final Path additionalDexJarFilesDir;

  /**
   * Directory under the project filesystem where this step may write temporary data. This directory
   * must exist and be empty before this step writes to it.
   */
  private final Path scratchDirectory;

  public PreDexedFilesSorter(
      ImmutableMultimap<APKModule, DexWithClasses> dexFilesToMerge,
      ImmutableSet<String> primaryDexPatterns,
      APKModuleGraph apkModuleGraph,
      Path scratchDirectory,
      long dexWeightLimit,
      DexStore dexStore,
      Path secondaryDexJarFilesDir,
      Path additionalDexJarFilesDir) {
    this.dexFilesToMerge = dexFilesToMerge;
    this.primaryDexFilter = ClassNameFilter.fromConfiguration(primaryDexPatterns);
    this.apkModuleGraph = apkModuleGraph;
    this.scratchDirectory = scratchDirectory;
    Preconditions.checkState(dexWeightLimit > 0);
    this.dexWeightLimit = dexWeightLimit;
    this.dexStore = dexStore;
    this.secondaryDexJarFilesDir = secondaryDexJarFilesDir;
    this.additionalDexJarFilesDir = additionalDexJarFilesDir;
  }

  public ImmutableMap<String, Result> sortIntoPrimaryAndSecondaryDexes(
      ProjectFilesystem filesystem, ImmutableList.Builder<Step> steps) {
    Map<APKModule, DexStoreContents> apkModuleDexesContents = new HashMap<>();
    DexStoreContents rootStoreContents =
        new DexStoreContents(apkModuleGraph.getRootAPKModule(), filesystem, steps);
    apkModuleDexesContents.put(apkModuleGraph.getRootAPKModule(), rootStoreContents);

    for (APKModule module : dexFilesToMerge.keySet()) {
      // Sort dex files so that there's a better chance of the same set of pre-dexed files to end up
      // in a given secondary dex file.
      ImmutableList<DexWithClasses> sortedDexFilesToMerge =
          FluentIterable.from(dexFilesToMerge.get(module))
              .toSortedList(DexWithClasses.DEX_WITH_CLASSES_COMPARATOR);

      // Bucket each DexWithClasses into the appropriate dex file.
      for (DexWithClasses dexWithClasses : sortedDexFilesToMerge) {
        if (module.equals(apkModuleGraph.getRootAPKModule())
            && mustBeInPrimaryDex(dexWithClasses)) {
          // Case 1: Entry must be in the primary dex.
          rootStoreContents.addPrimaryDex(dexWithClasses);
        } else {
          DexStoreContents storeContents = apkModuleDexesContents.get(module);
          if (storeContents == null) {
            storeContents = new DexStoreContents(module, filesystem, steps);
            apkModuleDexesContents.put(module, storeContents);
          }
          storeContents.addDex(dexWithClasses);
        }
      }
    }

    ImmutableMap.Builder<String, Result> resultBuilder = ImmutableMap.builder();

    for (DexStoreContents contents : apkModuleDexesContents.values()) {
      resultBuilder.put(contents.apkModule.getName(), contents.getResult());
    }

    return resultBuilder.build();
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
    private List<List<DexWithClasses>> dexesContents = new ArrayList<>();
    private int primaryDexSize;
    private List<DexWithClasses> primaryDexContents;
    private int currentDexSize;
    private List<DexWithClasses> currentDexContents;

    private final APKModule apkModule;
    private final ProjectFilesystem filesystem;
    private final ImmutableList.Builder<Step> steps;
    private final ImmutableMap.Builder<SourcePath, Sha1HashCode> dexInputsHashes =
        ImmutableMap.builder();

    public DexStoreContents(
        APKModule apkModule, ProjectFilesystem filesystem, ImmutableList.Builder<Step> steps) {
      this.filesystem = filesystem;
      this.steps = steps;
      this.apkModule = apkModule;
      currentDexSize = 0;
      currentDexContents = new ArrayList<>();
      primaryDexSize = 0;
      primaryDexContents = new ArrayList<>();
    }

    public void addPrimaryDex(DexWithClasses dexWithClasses) {
      primaryDexSize += dexWithClasses.getWeightEstimate();
      primaryDexContents.add(dexWithClasses);
      dexInputsHashes.put(dexWithClasses.getSourcePathToDexFile(), dexWithClasses.getClassesHash());
    }

    public void addDex(DexWithClasses dexWithClasses) {
      // If we're over the size threshold, start writing to a new dex
      if (dexWithClasses.getWeightEstimate() + currentDexSize > dexWeightLimit) {
        currentDexSize = 0;
        currentDexContents = new ArrayList<>();
      }

      // If this is the first class in the dex, initialize it with a canary and add it to the set of
      // dexes.
      if (currentDexContents.isEmpty()) {
        DexWithClasses canary =
            createCanary(
                filesystem, apkModule.getCanaryClassName(), dexesContents.size() + 1, steps);
        currentDexSize += canary.getWeightEstimate();
        currentDexContents.add(canary);

        dexesContents.add(currentDexContents);
        dexInputsHashes.put(canary.getSourcePathToDexFile(), canary.getClassesHash());
      }

      // Now add the contributions from the dexWithClasses entry.
      currentDexContents.add(dexWithClasses);
      dexInputsHashes.put(dexWithClasses.getSourcePathToDexFile(), dexWithClasses.getClassesHash());
      currentDexSize += dexWithClasses.getWeightEstimate();
    }

    Result getResult() {
      if (primaryDexSize > dexWeightLimit) {
        throwErrorForPrimaryDexExceedsWeightLimit();
      }

      Map<Path, DexWithClasses> metadataTxtEntries = new HashMap<>();
      ImmutableMultimap.Builder<Path, SourcePath> secondaryOutputToInputs =
          ImmutableMultimap.builder();
      boolean isRootModule = apkModule.equals(apkModuleGraph.getRootAPKModule());

      for (int index = 0; index < dexesContents.size(); index++) {
        Path pathToSecondaryDex;
        if (isRootModule) {
          pathToSecondaryDex =
              secondaryDexJarFilesDir.resolve(dexStore.fileNameForSecondary(index));
        } else {
          pathToSecondaryDex =
              additionalDexJarFilesDir
                  .resolve(apkModule.getName())
                  .resolve(dexStore.fileNameForSecondary(apkModule.getName(), index));
        }
        metadataTxtEntries.put(pathToSecondaryDex, dexesContents.get(index).get(0));
        Collection<SourcePath> dexContentPaths =
            Collections2.transform(
                dexesContents.get(index), DexWithClasses::getSourcePathToDexFile);
        secondaryOutputToInputs.putAll(pathToSecondaryDex, dexContentPaths);
      }

      ImmutableSet<SourcePath> primaryDexInputs =
          primaryDexContents
              .stream()
              .map(DexWithClasses::getSourcePathToDexFile)
              .collect(ImmutableSet.toImmutableSet());

      return new Result(
          apkModule,
          primaryDexInputs,
          secondaryOutputToInputs.build(),
          metadataTxtEntries,
          dexInputsHashes.build());
    }

    private void throwErrorForPrimaryDexExceedsWeightLimit() {
      StringBuilder message = new StringBuilder();
      message.append(
          String.format(
              "Primary dex weight %s exceeds limit of %s. It contains...%n",
              primaryDexSize, dexWeightLimit));
      message.append(String.format("Weight\tDex file path%n"));
      Comparator<DexWithClasses> bySizeDescending =
          (o1, o2) -> Integer.compare(o2.getWeightEstimate(), o1.getWeightEstimate());
      ImmutableList<DexWithClasses> sortedBySizeDescending =
          FluentIterable.from(primaryDexContents).toSortedList(bySizeDescending);
      for (DexWithClasses dex : sortedBySizeDescending) {
        message.append(
            String.format("%s\t%s%n", dex.getWeightEstimate(), dex.getSourcePathToDexFile()));
      }
      throw new HumanReadableException(message.toString());
    }

    /** @see com.facebook.buck.android.dalvik.CanaryFactory#create(String, int) */
    private DexWithClasses createCanary(
        ProjectFilesystem filesystem,
        String storeName,
        int index,
        ImmutableList.Builder<Step> steps) {
      FileLike fileLike = CanaryFactory.create(storeName, index);
      String canaryDirName = "canary_" + storeName + "_" + String.valueOf(index);
      Path scratchDirectoryForCanaryClass = scratchDirectory.resolve(canaryDirName);

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

  public static class Result {
    public final APKModule apkModule;
    public final Set<SourcePath> primaryDexInputs;
    public final Multimap<Path, SourcePath> secondaryOutputToInputs;
    public final Map<Path, DexWithClasses> metadataTxtDexEntries;
    public final ImmutableMap<SourcePath, Sha1HashCode> dexInputHashes;

    public Result(
        APKModule apkModule,
        Set<SourcePath> primaryDexInputs,
        Multimap<Path, SourcePath> secondaryOutputToInputs,
        Map<Path, DexWithClasses> metadataTxtDexEntries,
        ImmutableMap<SourcePath, Sha1HashCode> dexInputHashes) {
      this.apkModule = apkModule;
      this.primaryDexInputs = primaryDexInputs;
      this.secondaryOutputToInputs = secondaryOutputToInputs;
      this.metadataTxtDexEntries = metadataTxtDexEntries;
      this.dexInputHashes = dexInputHashes;
    }
  }
}
