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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Delegates the actual reading of disk-cached data to the {@link InitializableFromDisk} and is
 * responsible for safely storing and retrieving the in-memory data structures.
 */
public class BuildOutputInitializer<T> {

  private final BuildTarget buildTarget;
  private final InitializableFromDisk<T> initializableFromDisk;

  @Nullable
  private T buildOutput;

  public BuildOutputInitializer(
      BuildTarget buildTarget,
      InitializableFromDisk<T> initializableFromDisk) {
    this.buildTarget = buildTarget;
    this.initializableFromDisk = initializableFromDisk;
  }

  public T initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return initializableFromDisk.initializeFromDisk(onDiskBuildInfo);
  }

  /**
   * This should be invoked only by the build engine (currently, {@link CachingBuildEngine})
   * that invoked {@link #initializeFromDisk(OnDiskBuildInfo)}.
   * <p>
   * @throws IllegalStateException if this method has already been invoked.
   */
  public void setBuildOutput(T buildOutput) throws IllegalStateException {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s",
        buildTarget);
    this.buildOutput = buildOutput;
  }

  /**
   * @return the value passed to {@link #setBuildOutput(Object)}.
   * @throws IllegalStateException if {@link #setBuildOutput(Object)} has not been invoked yet.
   */
  public T getBuildOutput() throws IllegalStateException {
    Preconditions.checkState(this.buildOutput != null,
        "buildOutput must already be set for %s",
        buildTarget);
    return buildOutput;
  }
}
