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

import com.facebook.buck.event.LogEvent;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

class PreDexMergeStep implements Step {

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  static EnumSet<DxStep.Option> DX_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.NO_OPTIMIZE);

  private static Function<DexWithClasses, Path> TO_PATH = new Function<DexWithClasses, Path>() {
    @Override
    public Path apply(DexWithClasses input) {
      return input.getPathToDexFile();
    }
  };

  private final ImmutableList<DexWithClasses> dexFilesToMerge;
  private final Optional<DexWithClasses> dexWithClassesForRDotJava;
  private final String primaryDexPath;
  private final ImmutableSet<String> primaryDexSubstrings;
  private final Path secondaryDexMetadataTxt;
  private final String secondaryDexJarFilesDir;
  private final DexStore dexStore;
  private final long linearAllocHardLimit;

  // TODO(mbolin): Take a ZipSplitter.CanaryStrategy as a parameter. Or more importantly, apply
  // CanaryStrategy.INCLUDE_CANARIES by default.
  public PreDexMergeStep(ImmutableList<DexWithClasses> dexFilesToMerge,
      Optional<DexWithClasses> dexWithClassesForRDotJava,
      String primaryDexPath,
      ImmutableSet<String> primaryDexSubstrings,
      Path secondaryDexMetadataTxt,
      String secondaryDexJarFilesDir,
      DexStore dexStore,
      long linearAllocHardLimit) {
    this.dexFilesToMerge = Preconditions.checkNotNull(dexFilesToMerge);
    this.dexWithClassesForRDotJava = Preconditions.checkNotNull(dexWithClassesForRDotJava);
    this.primaryDexPath = Preconditions.checkNotNull(primaryDexPath);
    this.primaryDexSubstrings = Preconditions.checkNotNull(primaryDexSubstrings);
    this.secondaryDexMetadataTxt = Preconditions.checkNotNull(secondaryDexMetadataTxt);
    this.secondaryDexJarFilesDir = Preconditions.checkNotNull(secondaryDexJarFilesDir);
    this.dexStore = Preconditions.checkNotNull(dexStore);
    Preconditions.checkArgument(linearAllocHardLimit > 0);
    this.linearAllocHardLimit = linearAllocHardLimit;
  }

  @Override
  public int execute(ExecutionContext context) {
    int primaryDexSize = 0;
    List<DexWithClasses> primaryDexContents = Lists.newArrayList();
    // R.class files should always be in the primary dex.
    if (dexWithClassesForRDotJava.isPresent()) {
      primaryDexSize += dexWithClassesForRDotJava.get().getSizeEstimate();
      primaryDexContents.add(dexWithClassesForRDotJava.get());
    }

    // Bucket each DexWithClasses into the appropriate dex file.
    List<List<DexWithClasses>> secondaryDexesContents = Lists.newArrayList();
    List<DexWithClasses> currentSecondaryDexContents = null;
    int currentSecondaryDexSize = 0;
    for (DexWithClasses dexWithClasses : dexFilesToMerge) {
      // Case 1: Entry must be in the primary dex.
      if (mustBeInPrimaryDex(dexWithClasses)) {
        primaryDexSize += dexWithClasses.getSizeEstimate();
        if (primaryDexSize > linearAllocHardLimit) {
          context.postEvent(LogEvent.severe(
              "DexWithClasses %s with cost %s puts the linear alloc estimate for the primary dex " +
                  "at %s, exceeding the maximum of %s.",
              dexWithClasses.getPathToDexFile(),
              dexWithClasses.getSizeEstimate(),
              primaryDexSize,
              linearAllocHardLimit));
          return 1;
        }
        primaryDexContents.add(dexWithClasses);

      // Case 2: Entry must go in a new, secondary dex.
      } else if (currentSecondaryDexContents == null ||
          dexWithClasses.getSizeEstimate() + currentSecondaryDexSize > linearAllocHardLimit) {
        if (dexWithClasses.getSizeEstimate() > linearAllocHardLimit) {
          context.postEvent(LogEvent.severe(
              "DexWithClasses %s with cost %s exceeds the max cost %s for a secondary dex file.",
              dexWithClasses.getPathToDexFile(),
              dexWithClasses.getSizeEstimate(),
              linearAllocHardLimit));
          return 1;
        }

        // If there is no current secondary dex, or dexWithClasses would put the current secondary
        // dex over the cost threshold, then create a new secondary dex and initialize it with
        // dexWithClasses.
        currentSecondaryDexContents = Lists.newArrayList(dexWithClasses);
        currentSecondaryDexSize = dexWithClasses.getSizeEstimate();
        secondaryDexesContents.add(currentSecondaryDexContents);

      // Case 3 :Entry can fit in the current secondary dex.
      } else {
        currentSecondaryDexContents.add(dexWithClasses);
        currentSecondaryDexSize += dexWithClasses.getSizeEstimate();
      }
    }

    // Create a list of steps to do the dexing: these will be run in parallel.
    // There will always be at least one step for the primary classes.dex file.
    List<Step> dxSteps = Lists.newArrayList();
    dxSteps.add(createDxStep(primaryDexPath, primaryDexContents));

    // Keep track of where the secondary dex files are written for writing metadata.txt later.
    String pattern = "secondary-%d" + dexStore.getExtension();
    Map<Integer, Path> indexToPathToSecondaryDex = Maps.newHashMap();

    // Create the steps do dex the secondary dexes.
    for (int index = 0; index < secondaryDexesContents.size(); index++) {
      String name = String.format(pattern, index + 1);
      Path pathToSecondaryDex = Paths.get(secondaryDexJarFilesDir, name);
      indexToPathToSecondaryDex.put(index, pathToSecondaryDex);

      List<DexWithClasses> secondaryDex = secondaryDexesContents.get(index);
      dxSteps.add(SmartDexingStep.createDxStepForDxPseudoRule(
          Iterables.transform(secondaryDex, TO_PATH),
          pathToSecondaryDex.toString(),
          DX_OPTIONS));
    }

    // Run the dexing steps in parallel.
    StepRunner stepRunner = createStepRunner(context);
    try {
      stepRunner.runStepsInParallelAndWait(dxSteps);
    } catch (StepFailedException e) {
      context.logError(e, "Failed when dx-merging for multi-dex.");
      return 1;
    } finally {
      stepRunner.getListeningExecutorService().shutdownNow();
    }

    // Generate the metadata.txt file.
    try {
      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      List<String> lines = Lists.newArrayListWithCapacity(secondaryDexesContents.size());
      for (int index = 0; index < secondaryDexesContents.size(); index++) {
        Path pathToSecondaryDex = indexToPathToSecondaryDex.get(index);
        DexWithClasses dexWithClasses = Iterables.get(secondaryDexesContents.get(index), 0);
        String containedClass = Iterables.get(dexWithClasses.getClassNames(), 0);
        containedClass = containedClass.replace('/', '.');
        String hash = projectFilesystem.computeSha1(pathToSecondaryDex);
        lines.add(String.format("%s %s %s", pathToSecondaryDex.getFileName(), hash, containedClass));
      }
      projectFilesystem.writeLinesToPath(lines, secondaryDexMetadataTxt);
    } catch (IOException e) {
      context.logError(e, "Failed when writing metadata.txt multi-dex.");
      return 1;
    }

    return 0;
  }

  @VisibleForTesting
  protected StepRunner createStepRunner(ExecutionContext context) {
    ListeningExecutorService executorService = SmartDexingStep.createDxExecutor(
        /* numThreads */ Optional.<Integer>absent());
    DefaultStepRunner stepRunner = new DefaultStepRunner(context, executorService);
    return stepRunner;
  }

  private boolean mustBeInPrimaryDex(DexWithClasses dexWithClasses) {
    for (String className : dexWithClasses.getClassNames()) {
      for (String pattern : primaryDexSubstrings) {
        if (className.contains(pattern)) {
          return true;
        }
      }
    }
    return false;
  }

  private static DxStep createDxStep(String outputDexFile, Iterable<DexWithClasses> dexFiles) {
    return new DxStep(outputDexFile, Iterables.transform(dexFiles, TO_PATH), DX_OPTIONS);
  }

  @Override
  public String getShortName() {
    return "bucket_and_merge_dx";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "bucket_and_merge_dx";
  }

}
