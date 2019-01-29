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

package com.facebook.buck.android.dalvik;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.jvm.java.classes.FileLikes;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class EstimateDexWeightStep implements Step, Supplier<Integer> {

  @VisibleForTesting
  interface DexWeightEstimator {
    int getEstimate(FileLike fileLike) throws IOException;
  }

  /**
   * This is an estimator that uses the size of the {@code .class} file as the estimate. This should
   * be used when speed is more important than accuracy.
   */
  private static final DexWeightEstimator DEFAULT_ESTIMATOR = fileLike -> (int) fileLike.getSize();

  private final ProjectFilesystem filesystem;
  private final Path pathToJarOrClassesDirectory;
  private final DexWeightEstimator dexWeightEstimator;

  private int weightEstimate = -1;

  /**
   * This uses an estimator that uses the size of the {@code .class} file as the estimate. This
   * should be used when speed is more important than accuracy.
   */
  public EstimateDexWeightStep(ProjectFilesystem filesystem, Path pathToJarOrClassesDirectory) {
    this(filesystem, pathToJarOrClassesDirectory, DEFAULT_ESTIMATOR);
  }

  @VisibleForTesting
  EstimateDexWeightStep(
      ProjectFilesystem filesystem,
      Path pathToJarOrClassesDirectory,
      DexWeightEstimator dexWeightEstimator) {
    this.filesystem = filesystem;
    this.pathToJarOrClassesDirectory = pathToJarOrClassesDirectory;
    this.dexWeightEstimator = dexWeightEstimator;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Path path = filesystem.resolve(pathToJarOrClassesDirectory);
    AtomicInteger totalWeightEstimate = new AtomicInteger();
    ClasspathTraversal traversal =
        new ClasspathTraversal(Collections.singleton(path), filesystem) {

          @Override
          public void visit(FileLike fileLike) throws IOException {
            // When traversing a JAR file, it may have resources or directory entries that do not
            // end
            // in .class, which should be ignored.
            if (!FileLikes.isClassFile(fileLike)) {
              return;
            }

            totalWeightEstimate.addAndGet(dexWeightEstimator.getEstimate(fileLike));
          }
        };

    new DefaultClasspathTraverser().traverse(traversal);

    this.weightEstimate = totalWeightEstimate.get();
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public Integer get() {
    Preconditions.checkState(weightEstimate >= 0, "If less than zero, was not set.");
    return weightEstimate;
  }

  @Override
  public String getShortName() {
    return "estimate_dex_weight";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "estimate_dex_weight";
  }

  @VisibleForTesting
  public void setWeightEstimateForTesting(int weightEstimate) {
    this.weightEstimate = weightEstimate;
  }
}
