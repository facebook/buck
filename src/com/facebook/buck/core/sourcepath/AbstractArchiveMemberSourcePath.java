/*
 * Copyright 2016-present Facebook, Inc.
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import org.immutables.value.Value;

/** A {@link SourcePath} that can reference a member within an archive. */
@BuckStyleTuple
@Value.Immutable(prehash = true)
abstract class AbstractArchiveMemberSourcePath implements SourcePath {

  public abstract SourcePath getArchiveSourcePath();

  public abstract Path getMemberPath();

  @Value.Check
  public void check() {
    Preconditions.checkState(
        !getMemberPath().isAbsolute(),
        "ArchiveMemberSourcePath must not be absolute but was %s",
        getMemberPath());
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

    AbstractArchiveMemberSourcePath that = (AbstractArchiveMemberSourcePath) other;

    return ComparisonChain.start()
        .compare(getArchiveSourcePath(), that.getArchiveSourcePath())
        .compare(getMemberPath(), that.getMemberPath())
        .result();
  }
}
