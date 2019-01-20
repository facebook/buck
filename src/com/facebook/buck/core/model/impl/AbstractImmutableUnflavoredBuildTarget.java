/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.AbstractUnflavoredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.primitives.Booleans;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleImmutable
@Value.Immutable(copy = false)
abstract class AbstractImmutableUnflavoredBuildTarget extends AbstractUnflavoredBuildTarget {

  /** Interner for instances of UnflavoredBuildTarget. */
  private static final Interner<ImmutableUnflavoredBuildTarget> interner =
      Interners.newWeakInterner();

  /** Builder for UnflavoredBuildTargets which routes values through BuildTargetInterner. */
  public static class Builder extends ImmutableUnflavoredBuildTarget.Builder {
    @Override
    public ImmutableUnflavoredBuildTarget build() {
      return interner.intern(super.build());
    }
  }

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getBaseName().startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with %s but was %s",
        BUILD_TARGET_PREFIX,
        getBaseName());

    // BaseName may contain backslashes, which are the path separator, so not permitted.
    Preconditions.checkArgument(
        !getBaseName().contains("\\"), "baseName may not contain backslashes.");

    Preconditions.checkArgument(
        !getShortName().contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        getShortName());
  }

  @Override
  public abstract Path getCellPath();

  @Override
  public abstract Optional<String> getCell();

  @Override
  public abstract String getBaseName();

  @Override
  public abstract String getShortName();

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static UnflavoredBuildTarget of(
      Path cellPath, Optional<String> cellName, String baseName, String shortName) {
    return builder()
        .setCellPath(cellPath)
        .setCell(cellName)
        .setBaseName(baseName)
        .setShortName(shortName)
        .build();
  }

  @Override
  public int compareTo(UnflavoredBuildTarget o) {
    if (this == o) {
      return 0;
    }
    int cmp = Booleans.compare(o.getCell().isPresent(), getCell().isPresent());
    if (cmp != 0) {
      return cmp;
    }
    if (getCell().isPresent() && o.getCell().isPresent()) {
      cmp = MoreStrings.compareStrings(getCell().get(), o.getCell().get());
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = MoreStrings.compareStrings(getBaseName(), o.getBaseName());
    if (cmp != 0) {
      return cmp;
    }
    return MoreStrings.compareStrings(getShortName(), o.getShortName());
  }
}
