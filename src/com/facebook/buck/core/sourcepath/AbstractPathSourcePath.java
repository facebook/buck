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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import java.util.Objects;
import org.immutables.value.Value;

@BuckStyleTuple
@Value.Immutable(prehash = true)
public abstract class AbstractPathSourcePath implements SourcePath {

  protected abstract ProjectFilesystem getFilesystem();

  public abstract Path getRelativePath();

  @Override
  public int hashCode() {
    return Objects.hash(getFilesystem().getRootPath(), getRelativePath());
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AbstractPathSourcePath)) {
      return false;
    }
    AbstractPathSourcePath that = (AbstractPathSourcePath) other;
    return getRelativePath().equals(that.getRelativePath())
        && getFilesystem().getRootPath().equals(that.getFilesystem().getRootPath());
  }

  @Override
  public String toString() {
    return getFilesystem().getRootPath().resolve(getRelativePath().toString()).toString();
  }

  @Override
  public int compareTo(SourcePath other) {
    if (other == this) {
      return 0;
    }

    int classComparison = compareClasses(other);
    if (classComparison != 0) {
      return classComparison;
    }

    AbstractPathSourcePath that = (AbstractPathSourcePath) other;

    return ComparisonChain.start()
        .compare(getFilesystem().getRootPath(), that.getFilesystem().getRootPath())
        .compare(getRelativePath(), that.getRelativePath())
        .result();
  }
}
