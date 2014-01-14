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

import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.java.AccumulateClassNamesStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public class DexRDotJavaStep extends CompositeStep {

  private final Supplier<Integer> linearAllocSizeEstimate;
  private boolean stepFinished;

  private DexRDotJavaStep(List<Step> steps, Supplier<Integer> linearAllocSizeEstimate) {
    super(steps);
    this.linearAllocSizeEstimate = Preconditions.checkNotNull(linearAllocSizeEstimate);
    this.stepFinished = false;
  }

  @Override
  public int execute(ExecutionContext context) {
    int result = super.execute(context);
    stepFinished = result == 0;
    return result;
  }

  public Integer getLinearAllocSizeEstimate() {
    Preconditions.checkState(stepFinished,
        "Trying to access the linear alloc size estimate of dex output " +
            "before DexRDotJavaStep finished its execution successfully.");
    return linearAllocSizeEstimate.get();
  }

  public static DexRDotJavaStep create(BuildTarget buildTarget, Path pathToCompiledRDotJavaFiles) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path rDotJavaScratchDir = getRDotJavaScratchDir(buildTarget);
    steps.add(new MakeCleanDirectoryStep(rDotJavaScratchDir));
    final AccumulateClassNamesStep accumulateClassNames = new AccumulateClassNamesStep(
        Optional.of(pathToCompiledRDotJavaFiles),
        rDotJavaScratchDir.resolve("classes.txt"));
    steps.add(accumulateClassNames);

    Path rDotJavaDex = getPathToDexFile(buildTarget);

    steps.add(new DxStep(rDotJavaDex,
        /* filesToDex */ Collections.singleton(pathToCompiledRDotJavaFiles),
        EnumSet.of(DxStep.Option.NO_OPTIMIZE)));

    final EstimateLinearAllocStep estimateLinearAllocStep = new EstimateLinearAllocStep(
        pathToCompiledRDotJavaFiles);
    steps.add(estimateLinearAllocStep);

    Supplier<Integer> linearAllocSizeEstimate = new Supplier<Integer>() {
      @Override
      public Integer get() {
        return estimateLinearAllocStep.get();
      }
    };

    return new DexRDotJavaStep(steps.build(), linearAllocSizeEstimate);
  }

  private static Path getRDotJavaScratchDir(BuildTarget buildTarget) {
    return Paths.get(String.format("%s/%s__%s_r_dot_java_scratch__",
        BuckConstant.BIN_DIR,
        buildTarget.getBaseNameWithSlash(),
        buildTarget.getShortName()));
  }

  public static Path getPathToDexFile(BuildTarget buildTarget) {
    return getRDotJavaScratchDir(buildTarget).resolve("classes.dex.jar");
  }
}
