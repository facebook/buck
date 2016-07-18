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

import com.facebook.buck.dalvik.CanaryFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for bucketing pre-dexed objects into primary and secondary dex files.
 */
public class PreDexedFilesSorter {

  private final Optional<DexWithClasses> rDotJavaDex;
  private final ImmutableMultimap<APKModule, DexWithClasses> dexFilesToMerge;
  private final ClassNameFilter primaryDexFilter;
  private final APKModuleGraph apkModuleGraph;
  private final long linearAllocHardLimit;
  private final DexStore dexStore;
  private final Path secondaryDexJarFilesDir;
  private final Path additionalDexJarFilesDir;

  /**
   * Directory under the project filesystem where this step may write temporary data. This directory
   * must exist and be empty before this step writes to it.
   */
  private final Path scratchDirectory;

  public PreDexedFilesSorter(
      Optional<DexWithClasses> rDotJavaDex,
      ImmutableMultimap<APKModule, DexWithClasses> dexFilesToMerge,
      ImmutableSet<String> primaryDexPatterns,
      APKModuleGraph apkModuleGraph,
      Path scratchDirectory,
      long linearAllocHardLimit,
      DexStore dexStore,
      Path secondaryDexJarFilesDir,
      Path additionalDexJarFilesDir) {
    this.rDotJavaDex = rDotJavaDex;
    this.dexFilesToMerge = dexFilesToMerge;
    this.primaryDexFilter = ClassNameFilter.fromConfiguration(primaryDexPatterns);
    this.apkModuleGraph = apkModuleGraph;
    this.scratchDirectory = scratchDirectory;
    Preconditions.checkState(linearAllocHardLimit > 0);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.dexStore = dexStore;
    this.secondaryDexJarFilesDir = secondaryDexJarFilesDir;
    this.additionalDexJarFilesDir = additionalDexJarFilesDir;
  }

  public ImmutableMap<String, Result> sortIntoPrimaryAndSecondaryDexes(
      BuildContext context,
      ProjectFilesystem filesystem,
      ImmutableList.Builder<Step> steps) {
    Map<APKModule, DexStoreContents> apkModuleDexesContents =
        new HashMap<>();
    DexStoreContents rootStoreContents =
        new DexStoreContents(apkModuleGraph.getRootAPKModule(), context, filesystem, steps);
    apkModuleDexesContents.put(
        apkModuleGraph.getRootAPKModule(),
        rootStoreContents);

    // R.class files should always be in the primary dex.
    if (rDotJavaDex.isPresent()) {
      rootStoreContents.addPrimaryDex(rDotJavaDex.get());
    }

    for (APKModule module : dexFilesToMerge.keySet()) {
      // Sort dex files so that there's a better chance of the same set of pre-dexed files to end up
      // in a given secondary dex file.
      ImmutableList<DexWithClasses> sortedDexFilesToMerge = FluentIterable
          .from(dexFilesToMerge.get(module))
          .toSortedList(DexWithClasses.DEX_WITH_CLASSES_COMPARATOR);

      // Bucket each DexWithClasses into the appropriate dex file.
      for (DexWithClasses dexWithClasses : sortedDexFilesToMerge) {
        if (module.equals(apkModuleGraph.getRootAPKModule()) &&
            mustBeInPrimaryDex(dexWithClasses)) {
          // Case 1: Entry must be in the primary dex.
          rootStoreContents.addPrimaryDex(dexWithClasses);
        } else {
          DexStoreContents storeContents = apkModuleDexesContents.get(module);
          if (storeContents == null) {
            storeContents = new DexStoreContents(module, context, filesystem, steps);
            apkModuleDexesContents.put(module, storeContents);
          }
          storeContents.addDex(dexWithClasses);
        }
      }
    }

    ImmutableMap.Builder<String, Result> resultBuilder = ImmutableMap.builder();

    for (DexStoreContents contents : apkModuleDexesContents.values()) {
      resultBuilder.put(
            contents.apkModule.getName(),
            contents.getResult());
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
    private List<List<DexWithClasses>> dexesContents;
    private int primaryDexSize;
    private List<DexWithClasses> primaryDexContents;
    private int currentDexSize;
    private List<DexWithClasses> currentDexContents;

    private final APKModule apkModule;
    private final BuildContext context;
    private final ProjectFilesystem filesystem;
    private final ImmutableList.Builder<Step> steps;
    private final ImmutableMap.Builder<Path, Sha1HashCode> dexInputsHashes = ImmutableMap.builder();

    public DexStoreContents(
        APKModule apkModule,
        BuildContext context,
        ProjectFilesystem filesystem,
        ImmutableList.Builder<Step> steps) {
      this.context = context;
      this.filesystem = filesystem;
      this.steps = steps;
      this.apkModule = apkModule;
      dexesContents = Lists.newArrayList();
      currentDexSize = 0;
      currentDexContents = Lists.newArrayList();
      primaryDexSize = 0;
      primaryDexContents = Lists.newArrayList();
    }

    public void addPrimaryDex(DexWithClasses dexWithClasses) {
      primaryDexSize += dexWithClasses.getSizeEstimate();
      if (primaryDexSize > linearAllocHardLimit) {
        context.logError(
            "DexWithClasses %s with cost %s puts the linear alloc estimate for the primary dex " +
                "at %s, exceeding the maximum of %s.",
            dexWithClasses.getPathToDexFile(),
            dexWithClasses.getSizeEstimate(),
            primaryDexSize,
            linearAllocHardLimit);
        throw new HumanReadableException("Primary dex exceeds linear alloc limit.");
      }
      this.primaryDexContents.add(dexWithClasses);
      dexInputsHashes.put(dexWithClasses.getPathToDexFile(), dexWithClasses.getClassesHash());
    }

    public void addDex(DexWithClasses dexWithClasses) {
      // If the individual DexWithClasses exceeds the limit for a secondary dex, then we have done
      // something horribly wrong.
      if (dexWithClasses.getSizeEstimate() > linearAllocHardLimit) {
        context.logError(
            "DexWithClasses %s with cost %s exceeds the max cost %s for a secondary dex file.",
            dexWithClasses.getPathToDexFile(),
            dexWithClasses.getSizeEstimate(),
            linearAllocHardLimit);
        throw new HumanReadableException("Secondary dex exceeds linear alloc limit.");
      }

      // If we're over the size threshold, start writing to a new dex
      if (dexWithClasses.getSizeEstimate() + currentDexSize > linearAllocHardLimit) {
        currentDexSize = 0;
        currentDexContents = Lists.newArrayList();
      }

      // If this is the first class in the dex, initialize it with a canary and add it to the set of
      // dexes.
      if (currentDexContents.size() == 0) {
        DexWithClasses canary = createCanary(
            filesystem,
            apkModule.getCanaryClassName(),
            dexesContents.size() + 1,
            steps);
        currentDexSize += canary.getSizeEstimate();

        dexesContents.add(currentDexContents);
        dexInputsHashes.put(canary.getPathToDexFile(), canary.getClassesHash());
      }

      // Now add the contributions from the dexWithClasses entry.
      currentDexContents.add(dexWithClasses);
      dexInputsHashes.put(dexWithClasses.getPathToDexFile(), dexWithClasses.getClassesHash());
      currentDexSize += dexWithClasses.getSizeEstimate();
    }

    Result getResult() {
      Map<Path, DexWithClasses> metadataTxtEntries = Maps.newHashMap();
      ImmutableMultimap.Builder<Path, Path> secondaryOutputToInputs = ImmutableMultimap.builder();
      boolean isRootModule = apkModule.equals(apkModuleGraph.getRootAPKModule());

      for (int index = 0; index < dexesContents.size(); index++) {
        Path pathToSecondaryDex;
        if (isRootModule) {
          pathToSecondaryDex = secondaryDexJarFilesDir
              .resolve(dexStore.fileNameForSecondary(index));
        } else {
          pathToSecondaryDex = additionalDexJarFilesDir
              .resolve(apkModule.getName())
              .resolve(dexStore.fileNameForSecondary(apkModule.getName(), index));
        }
        metadataTxtEntries.put(
            pathToSecondaryDex,
            dexesContents.get(index).get(0));
        Collection<Path> dexContentPaths = Collections2.transform(
            dexesContents.get(index),
            DexWithClasses.TO_PATH);
        secondaryOutputToInputs.putAll(pathToSecondaryDex, dexContentPaths);
      }

      ImmutableSet<Path> primaryDexInputs = FluentIterable.from(primaryDexContents)
          .transform(DexWithClasses.TO_PATH)
          .toSet();

      return new Result(
          apkModule,
          primaryDexInputs,
          secondaryOutputToInputs.build(),
          metadataTxtEntries,
          dexInputsHashes.build());
    }

    /**
     * @see com.facebook.buck.dalvik.CanaryFactory#create(String, int)
     */
    private DexWithClasses createCanary(
        final ProjectFilesystem filesystem,
        String storeName,
        final int index,
        ImmutableList.Builder<Step> steps) {
      final FileLike fileLike = CanaryFactory.create(storeName, index);
      final String canaryDirName = "canary_" + storeName + "_" + String.valueOf(index);
      final Path scratchDirectoryForCanaryClass = scratchDirectory.resolve(canaryDirName);

      // Strip the .class suffix to get the class name for the DexWithClasses object.
      final String relativePathToClassFile = fileLike.getRelativePath();
      Preconditions.checkState(relativePathToClassFile.endsWith(".class"));
      final String className = relativePathToClassFile.replaceFirst("\\.class$", "");

      // Write out the .class file.
      steps.add(new AbstractExecutionStep("write_canary_class") {
        @Override
        public StepExecutionResult execute(ExecutionContext context) {
          Path classFile = scratchDirectoryForCanaryClass.resolve(relativePathToClassFile);
          try (InputStream inputStream = fileLike.getInput()) {
            filesystem.createParentDirs(classFile);
            filesystem.copyToPath(inputStream, classFile);
          } catch (IOException e) {
            context.logError(e,  "Error writing canary class file: %s.",  classFile.toString());
            return StepExecutionResult.ERROR;
          }
          return StepExecutionResult.SUCCESS;
        }
      });

      return new DexWithClasses() {

        @Override
        public int getSizeEstimate() {
          // Because we do not know the units being used for DEX size estimation and the canary
          // should be very small, assume the size is zero.
          return 0;
        }

        @Override
        public Path getPathToDexFile() {
          return scratchDirectoryForCanaryClass;
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
    public final Set<Path> primaryDexInputs;
    public final Multimap<Path, Path> secondaryOutputToInputs;
    public final Map<Path, DexWithClasses> metadataTxtDexEntries;
    public final ImmutableMap<Path, Sha1HashCode> dexInputHashes;

    public Result(
        APKModule apkModule,
        Set<Path> primaryDexInputs,
        Multimap<Path, Path> secondaryOutputToInputs,
        Map<Path, DexWithClasses> metadataTxtDexEntries,
        final ImmutableMap<Path, Sha1HashCode> dexInputHashes) {
      this.apkModule = apkModule;
      this.primaryDexInputs = primaryDexInputs;
      this.secondaryOutputToInputs = secondaryOutputToInputs;
      this.metadataTxtDexEntries = metadataTxtDexEntries;
      this.dexInputHashes = dexInputHashes;
    }
  }
}
