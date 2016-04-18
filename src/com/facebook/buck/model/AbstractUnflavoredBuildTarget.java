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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;

import org.immutables.value.Value;

import java.nio.file.Path;

@BuckStyleImmutable
@Value.Immutable
abstract class AbstractUnflavoredBuildTarget implements Comparable<AbstractUnflavoredBuildTarget> {

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
        !getBaseName().contains("\\"),
        "baseName may not contain backslashes.");

    Preconditions.checkArgument(
        getShortName().lastIndexOf("#") == -1,
        "Build target name cannot contain '#' but was: %s.",
        getShortName());

    Preconditions.checkArgument(
        !getShortName().contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        getShortName());
  }

  @Value.Parameter
  public abstract Path getCellPath();

  @Value.Parameter
  public abstract Optional<String> getCell();

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  @Value.Parameter
  public abstract String getBaseName();

  @Value.Parameter
  public abstract String getShortName();

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return (getCell().isPresent() ? getCell().get() : "") +
        getBaseName() + ":" + getShortName();
  }

  /**
   * Helper function for getting BuildTarget base names with a trailing slash if needed.
   *
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava/".
   */
  public String getBaseNameWithSlash() {
    String baseName = getBaseName();
    return baseName.equals(BUILD_TARGET_PREFIX) ? baseName : baseName + "/";
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return the
   * {@link Path} "third_party/java/guava". This does not contain the "//" prefix so that it can be
   * appended to a file path.
   */
  public Path getBasePath() {
    return getCellPath().getFileSystem().getPath(
        getBaseName().substring(BUILD_TARGET_PREFIX.length()));
  }

  /**
   * @return the value of {@link #getBasePath()} with a trailing slash, unless
   *     {@link #getBasePath()} returns the empty string, in which case this also returns the empty
   *     string
   */
  public String getBasePathWithSlash() {
    String basePath = MorePaths.pathWithUnixSeparators(getBasePath());
    return basePath.isEmpty() ? "" : basePath + "/";
  }

  public static UnflavoredBuildTarget.Builder builder(UnflavoredBuildTarget buildTarget) {
    return UnflavoredBuildTarget
        .builder()
        .setCell(buildTarget.getCell())
        .setBaseName(buildTarget.getBaseName())
        .setShortName(buildTarget.getShortName());
  }

  public static UnflavoredBuildTarget.Builder builder(String baseName, String shortName) {
    return UnflavoredBuildTarget
        .builder()
        .setCell(Optional.<String>absent())
        .setBaseName(baseName)
        .setShortName(shortName);
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

    ComparisonChain comparison = ComparisonChain.start()
        .compareTrueFirst(getCell().isPresent(), o.getCell().isPresent());
    if (getCell().isPresent() && o.getCell().isPresent()) {
      comparison = comparison.compare(getCell().get(), o.getCell().get());
    }
    return comparison
        .compare(getBaseName(), o.getBaseName())
        .compare(getShortName(), o.getShortName())
        .result();
  }
}
