/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ComparisonChain;

/**
 * A pair of {@link CanonicalCellName} and {@link ForwardRelativePath} relative the the cell.
 *
 * <p>This object can identify a buck package or a buck file.
 */
@BuckStyleValue
public abstract class CellRelativePath implements Comparable<CellRelativePath> {

  public abstract CanonicalCellName getCellName();

  public abstract ForwardRelativePath getPath();

  public boolean startsWith(CellRelativePath other) {
    return this.getCellName().equals(other.getCellName())
        && this.getPath().startsWith(other.getPath());
  }

  @Override
  public String toString() {
    return getCellName() + "//" + getPath();
  }

  @Override
  public int compareTo(CellRelativePath that) {
    return ComparisonChain.start()
        .compare(this.getCellName(), that.getCellName())
        .compare(this.getPath(), that.getPath())
        .result();
  }

  public static CellRelativePath of(CanonicalCellName cellName, ForwardRelativePath path) {
    return ImmutableCellRelativePath.of(cellName, path);
  }
}
