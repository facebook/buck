/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.CompositeStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Deletes the directory, if it exists, before creating it.
 * {@link MakeCleanDirectoryStep} is preferable to {@link MkdirStep} if the directory may
 * contain many generated files and we want to avoid the case where it could accidentally include
 * generated files from a previous run in Buck.
 * <p>
 * For example, for a directory of {@code .class} files, if the user deletes a {@code .java} file
 * that generated one of the {@code .class} files, the {@code .class} file corresponding to the
 * deleted {@code .java} file should no longer be there when {@code javac} is run again.
 */
public final class MakeCleanDirectoryStep extends CompositeStep {

  private final String pathRelativeToProjectRoot;

  public MakeCleanDirectoryStep(Path pathRelativeToProjectRoot) {
    super(ImmutableList.of(
        new RmStep(pathRelativeToProjectRoot,
            /* shouldForceDeletion */ true,
            /* shouldRecurse */ true),
        new MkdirStep(pathRelativeToProjectRoot)));
    this.pathRelativeToProjectRoot = pathRelativeToProjectRoot.toString();
  }

  @VisibleForTesting
  public String getPath() {
    return pathRelativeToProjectRoot;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof MakeCleanDirectoryStep)) {
      return false;
    }
    MakeCleanDirectoryStep that = (MakeCleanDirectoryStep)obj;
    return Objects.equal(this.pathRelativeToProjectRoot, that.pathRelativeToProjectRoot);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(pathRelativeToProjectRoot);
  }
}
