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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * Ensures the directory of the target path is created before a file is symlinked to it.
 */
public final class MkdirAndSymlinkFileStep extends CompositeStep {

  private final Path source;
  private final Path target;

  public MkdirAndSymlinkFileStep(Path source, Path target) {
    super(ImmutableList.of(
        new MkdirStep(target.getParent()),
        new SymlinkFileStep(source, target, /* useAbsolutePaths */ true)
    ));
    this.source = Preconditions.checkNotNull(source);
    this.target = Preconditions.checkNotNull(target);
  }

  @VisibleForTesting
  public Path getSource() {
    return source;
  }

  @VisibleForTesting
  public Path getTarget() {
    return target;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof MkdirAndSymlinkFileStep)) {
      return false;
    }
    MkdirAndSymlinkFileStep that = (MkdirAndSymlinkFileStep)obj;
    return Objects.equal(this.source, that.source)
        && Objects.equal(this.target, that.target);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.source, this.target);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(MkdirAndSymlinkFileStep.class)
        .add("source", source)
        .add("target", target)
        .toString();
  }
}
