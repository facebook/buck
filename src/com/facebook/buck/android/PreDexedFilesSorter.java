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

import static com.facebook.buck.android.SmartDexingStep.DexInputHashesProvider;

import com.facebook.buck.dalvik.CanaryFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.classes.FileLike;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
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
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for bucketing pre-dexed objects into primary and secondary dex files.
 */
public class PreDexedFilesSorter {

  private final Optional<DexWithClasses> rDotJavaDex;
  private final List<DexWithClasses> dexFilesToMerge;
  private final ClassNameFilter primaryDexFilter;
  private final long linearAllocHardLimit;
  private final DexStore dexStore;
  private final Path secondaryDexJarFilesDir;

  /**
   * Directory under the project filesystem where this step may write temporary data. This directory
   * must exist and be empty before this step writes to it.
   */
  private final Path scratchDirectory;

  public PreDexedFilesSorter(
      Optional<DexWithClasses> rDotJavaDex,
      List<DexWithClasses> dexFilesToMerge,
      ImmutableSet<String> primaryDexPatterns,
      Path scratchDirectory,
      long linearAllocHardLimit,
      DexStore dexStore,
      Path secondaryDexJarFilesDir) {
    this.rDotJavaDex = rDotJavaDex;
    this.dexFilesToMerge = dexFilesToMerge;
    this.primaryDexFilter = ClassNameFilter.fromConfiguration(primaryDexPatterns);
    this.scratchDirectory = scratchDirectory;
    Preconditions.checkState(linearAllocHardLimit > 0);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.dexStore = dexStore;
    this.secondaryDexJarFilesDir = secondaryDexJarFilesDir;
  }

  public Result sortIntoPrimaryAndSecondaryDexes(
      BuildContext context,
      ImmutableList.Builder<Step> steps) {
    List<DexWithClasses> primaryDexContents = Lists.newArrayList();
    List<List<DexWithClasses>> secondaryDexesContents = Lists.newArrayList();

    int primaryDexSize = 0;
    // R.class files should always be in the primary dex.
    if (rDotJavaDex.isPresent()) {
      primaryDexSize += rDotJavaDex.get().getSizeEstimate();
      primaryDexContents.add(rDotJavaDex.get());
    }

    // Sort dex files so that there's a better chance of the same set of pre-dexed files to end up
    // in a given secondary dex file.
    ImmutableList<DexWithClasses> sortedDexFilesToMerge = FluentIterable.from(dexFilesToMerge)
        .toSortedList(DexWithClasses.DEX_WITH_CLASSES_COMPARATOR);

    // Bucket each DexWithClasses into the appropriate dex file.
    List<DexWithClasses> currentSecondaryDexContents = null;
    int currentSecondaryDexSize = 0;
    for (DexWithClasses dexWithClasses : sortedDexFilesToMerge) {
      if (mustBeInPrimaryDex(dexWithClasses)) {
        // Case 1: Entry must be in the primary dex.
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
        primaryDexContents.add(dexWithClasses);
      } else {
        // Case 2: Entry must go in a secondary dex.

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

        // If there is no current secondary dex, or dexWithClasses would put the current secondary
        // dex over the cost threshold, then create a new secondary dex and initialize it with a
        // canary.
        if (currentSecondaryDexContents == null ||
            dexWithClasses.getSizeEstimate() + currentSecondaryDexSize > linearAllocHardLimit) {
          DexWithClasses canary = createCanary(secondaryDexesContents.size() + 1, steps);

          currentSecondaryDexContents = Lists.newArrayList(canary);
          currentSecondaryDexSize = canary.getSizeEstimate();
          secondaryDexesContents.add(currentSecondaryDexContents);
        }

        // Now add the contributions from the dexWithClasses entry.
        currentSecondaryDexContents.add(dexWithClasses);
        currentSecondaryDexSize += dexWithClasses.getSizeEstimate();
      }
    }

    ImmutableSet<Path> primaryDexInputs = FluentIterable.from(primaryDexContents)
        .transform(DexWithClasses.TO_PATH)
        .toSet();

    Map<Path, DexWithClasses> metadataTxtEntries = Maps.newHashMap();

    ImmutableMultimap.Builder<Path, Path> secondaryOutputToInputs = ImmutableMultimap.builder();
    for (int index = 0; index < secondaryDexesContents.size(); index++) {
      String secondaryDexFilename = dexStore.fileNameForSecondary(index);
      Path pathToSecondaryDex = secondaryDexJarFilesDir.resolve(secondaryDexFilename);
      metadataTxtEntries.put(pathToSecondaryDex, secondaryDexesContents.get(index).get(0));
      Collection<Path> dexContentPaths = Collections2.transform(
          secondaryDexesContents.get(index), DexWithClasses.TO_PATH);
      secondaryOutputToInputs.putAll(pathToSecondaryDex, dexContentPaths);
    }

    return new Result(
        primaryDexInputs,
        secondaryOutputToInputs.build(),
        metadataTxtEntries,
        getDexInputsHashes(primaryDexContents, secondaryDexesContents));
  }

  private static ImmutableMap<Path, Sha1HashCode> getDexInputsHashes(
      List<DexWithClasses> primaryDexContents,
      List<List<DexWithClasses>> secondaryDexesContents) {
    Iterable<DexWithClasses> allInputs = Iterables.concat(
        primaryDexContents,
        Iterables.concat(secondaryDexesContents));

    ImmutableMap.Builder<Path, Sha1HashCode> dexInputsHashes = ImmutableMap.builder();
    for (DexWithClasses dexWithClasses : allInputs) {
      dexInputsHashes.put(dexWithClasses.getPathToDexFile(), dexWithClasses.getClassesHash());
    }
    return dexInputsHashes.build();
  }

  private boolean mustBeInPrimaryDex(DexWithClasses dexWithClasses) {
    for (String className : dexWithClasses.getClassNames()) {
      if (primaryDexFilter.matches(className)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @see com.facebook.buck.dalvik.CanaryFactory#create(int)
   */
  private DexWithClasses createCanary(final int index, ImmutableList.Builder<Step> steps) {
    final FileLike fileLike = CanaryFactory.create(index);
    final String canaryDirName = "canary_" + String.valueOf(index);
    final Path scratchDirectoryForCanaryClass = scratchDirectory.resolve(canaryDirName);

    // Strip the .class suffix to get the class name for the DexWithClasses object.
    final String relativePathToClassFile = fileLike.getRelativePath();
    Preconditions.checkState(relativePathToClassFile.endsWith(".class"));
    final String className = relativePathToClassFile.replaceFirst("\\.class$", "");

    // Write out the .class file.
    steps.add(new AbstractExecutionStep("write_canary_class") {
      @Override
      public int execute(ExecutionContext context) {
        Path classFile = scratchDirectoryForCanaryClass.resolve(relativePathToClassFile);
        ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
        try (InputStream inputStream = fileLike.getInput()) {
          projectFilesystem.createParentDirs(classFile);
          projectFilesystem.copyToPath(inputStream, classFile);
        } catch (IOException e) {
          context.logError(e,  "Error writing canary class file: %s.",  classFile.toString());
          return 1;
        }
        return 0;
      }
    });

    return new DexWithClasses() {

      @Override
      public int getSizeEstimate() {
        // Because we do not know the units being used for DEX size estimation and the canary should
        // be very small, assume the size is zero.
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
        // The only thing unique to canary classes is the index, which is captured by canaryDirName.
        Hasher hasher = Hashing.sha1().newHasher();
        hasher.putString(canaryDirName, Charsets.UTF_8);
        return ImmutableSha1HashCode.of(hasher.hash().toString());
      }
    };
  }

  public static class Result {
    public final Set<Path> primaryDexInputs;
    public final Multimap<Path, Path> secondaryOutputToInputs;
    public final Map<Path, DexWithClasses> metadataTxtDexEntries;
    public final DexInputHashesProvider dexInputHashesProvider;

    public Result(
        Set<Path> primaryDexInputs,
        Multimap<Path, Path> secondaryOutputToInputs,
        Map<Path, DexWithClasses> metadataTxtDexEntries,
        final ImmutableMap<Path, Sha1HashCode> dexInputHashes) {
      this.primaryDexInputs = primaryDexInputs;
      this.secondaryOutputToInputs = secondaryOutputToInputs;
      this.metadataTxtDexEntries = metadataTxtDexEntries;
      this.dexInputHashesProvider = new DexInputHashesProvider() {
        @Override
        public ImmutableMap<Path, Sha1HashCode> getDexInputHashes() {
          return dexInputHashes;
        }
      };
    }
  }
}
