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

package com.facebook.buck.model;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.string.StringsUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.primitives.Booleans;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * A build target in the form of
 *
 * <pre>cell//path:rule</pre>
 *
 * .
 */
@BuckStyleImmutable
@Value.Immutable(copy = false)
abstract class AbstractUnflavoredBuildTarget implements Comparable<AbstractUnflavoredBuildTarget> {

  /** Interner for instances of UnflavoredBuildTarget. */
  private static final Interner<UnflavoredBuildTarget> interner = Interners.newWeakInterner();

  /** Builder for UnflavoredBuildTargets which routes values through BuildTargetInterner. */
  public static class Builder extends UnflavoredBuildTarget.Builder {
    @Override
    public UnflavoredBuildTarget build() {
      return interner.intern(super.build());
    }
  }

  public static final String BUILD_TARGET_PREFIX = "//";

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getBaseName().startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with %s but was %s",
        BUILD_TARGET_PREFIX,
        getBaseName());

    // On Windows, baseName may contain backslashes, which are not permitted.
    Preconditions.checkArgument(
        !getBaseName().contains("\\"), "baseName may not contain backslashes.");

    Preconditions.checkArgument(
        !getShortName().contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        getShortName());
  }

  // TODO: remove cell root path from this object. Don't forget to remove TODOs from
  // BuildTargetMacro after that
  public abstract Path getCellPath();

  public abstract Optional<String> getCell();

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  public abstract String getBaseName();

  public abstract String getShortName();

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return (getCell().isPresent() ? getCell().get() : "") + getBaseName() + ":" + getShortName();
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return the
   * {@link Path} "third_party/java/guava". This does not contain the "//" prefix so that it can be
   * appended to a file path.
   */
  public Path getBasePath() {
    return getCellPath()
        .getFileSystem()
        .getPath(getBaseName().substring(BUILD_TARGET_PREFIX.length()));
  }

  public boolean isInCellRoot() {
    return getBaseName().equals("//");
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

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public int compareTo(AbstractUnflavoredBuildTarget o) {
    if (this == o) {
      return 0;
    }
    int cmp = Booleans.compare(o.getCell().isPresent(), getCell().isPresent());
    if (cmp != 0) {
      return cmp;
    }
    if (getCell().isPresent() && o.getCell().isPresent()) {
      cmp = StringsUtils.compareStrings(getCell().get(), o.getCell().get());
      if (cmp != 0) {
        return cmp;
      }
    }
    cmp = StringsUtils.compareStrings(getBaseName(), o.getBaseName());
    if (cmp != 0) {
      return cmp;
    }
    return StringsUtils.compareStrings(getShortName(), o.getShortName());
  }
}
