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

package com.facebook.buck.rules;

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Delegates the actual reading of disk-cached data to the {@link InitializableFromDisk} and is
 * responsible for safely storing and retrieving the in-memory data structures.
 */
public final class BuildOutputInitializer<T> {

  private final BuildTarget buildTarget;
  private final InitializableFromDisk<T> initializableFromDisk;

  @Nullable private T buildOutput;

  public BuildOutputInitializer(
      BuildTarget buildTarget, InitializableFromDisk<T> initializableFromDisk) {
    this.buildTarget = buildTarget;
    this.initializableFromDisk = initializableFromDisk;
  }

  /**
   * Invalidates the cached build output. This should be called whenever the on disk state is about
   * to change.
   */
  public void invalidate() {
    buildOutput = null;
  }

  /** Initializes the build output from the on disk state. */
  public void initializeFromDisk() throws IOException {
    if (buildOutput == null) {
      buildOutput = initializableFromDisk.initializeFromDisk();
    }
  }

  /**
   * This should be invoked only by the build engine (currently, {@link
   * com.facebook.buck.core.build.engine.impl.CachingBuildEngine}) that invoked {@link
   * #initializeFromDisk()}.
   *
   * <p>
   *
   * @throws IllegalStateException if this method has already been invoked.
   */
  @VisibleForTesting
  public void setBuildOutputForTests(T buildOutput) throws IllegalStateException {
    this.buildOutput = buildOutput;
  }

  /** The initialized buildOutput. */
  public T getBuildOutput() throws IllegalStateException {
    if (buildOutput == null) {
      throw new IllegalStateException(
          String.format("buildOutput must already be set for %s", buildTarget));
    }
    return buildOutput;
  }
}
