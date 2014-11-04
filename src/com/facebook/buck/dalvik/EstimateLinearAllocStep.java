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

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.ClasspathTraversal;
import com.facebook.buck.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.java.classes.FileLike;
import com.facebook.buck.java.classes.FileLikes;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

public class EstimateLinearAllocStep implements Step, Supplier<Integer> {

  @VisibleForTesting
  static interface LinearAllocEstimator {
    public int getEstimate(FileLike fileLike) throws IOException;
  }

  /**
   * This is a linear alloc estimator that uses the size of the {@code .class} file as the
   * estimate. This should be used when speed is more important than accuracy. If accuracy is a
   * priority, an estimator based on {@link DalvikStatsTool#getEstimate(java.io.InputStream)} should
   * be used instead.
   */
  private static final LinearAllocEstimator DEFAULT_LINEAR_ALLOC_ESTIMATOR =
      new LinearAllocEstimator() {
    @Override
    public int getEstimate(FileLike fileLike) throws IOException {
      return (int) fileLike.getSize();
    }
  };

  private final Path pathToJarOrClassesDirectory;
  private final LinearAllocEstimator linearAllocEstimator;

  private int linearAllocEstimate = -1;

  /**
   * This uses a linear alloc estimator that uses the size of the {@code .class} file as the
   * estimate. This should be used when speed is more important than accuracy. If accuracy is a
   * priority, an estimator based on {@link DalvikStatsTool#getEstimate(java.io.InputStream)} should
   * be used instead.
   */
  public EstimateLinearAllocStep(Path pathToJarOrClassesDirectory) {
    this(pathToJarOrClassesDirectory, DEFAULT_LINEAR_ALLOC_ESTIMATOR);
  }

  @VisibleForTesting
  EstimateLinearAllocStep(Path pathToJarOrClassesDirectory,
      LinearAllocEstimator linearAllocEstimator) {
    this.pathToJarOrClassesDirectory = pathToJarOrClassesDirectory;
    this.linearAllocEstimator = linearAllocEstimator;
  }

  @Override
  public int execute(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    Path path = filesystem.resolve(pathToJarOrClassesDirectory);
    ClasspathTraversal traversal = new ClasspathTraversal(Collections.singleton(path), filesystem) {

      private int totalLinearAllocEstimate = 0;

      @Override
      public void visit(FileLike fileLike) throws IOException {
        // When traversing a JAR file, it may have resources or directory entries that do not end
        // in .class, which should be ignored.
        if (!FileLikes.isClassFile(fileLike)) {
          return;
        }

        totalLinearAllocEstimate += linearAllocEstimator.getEstimate(fileLike);
      }

      @Override
      public Integer getResult() {
        return totalLinearAllocEstimate;
      }
    };

    try {
      new DefaultClasspathTraverser().traverse(traversal);
    } catch (IOException e) {
      context.logError(e, "Error accumulating class names for %s.", pathToJarOrClassesDirectory);
      return 1;
    }

    this.linearAllocEstimate = (Integer) traversal.getResult();
    return 0;
  }

  @Override
  public Integer get() {
    Preconditions.checkState(linearAllocEstimate >= 0, "If less than zero, was not set.");
    return linearAllocEstimate;
  }

  @Override
  public String getShortName() {
    return "estimate_linear_alloc";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "estimate_linear_alloc";
  }

  @VisibleForTesting
  public void setLinearAllocEstimateForTesting(int linearAllocEstimate) {
    this.linearAllocEstimate = linearAllocEstimate;
  }
}
