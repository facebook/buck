/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.api;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Syntactic representation of a buck target.
 *
 * @see <a href=https://buckbuild.com/concept/build_target.html">the Buck documentation for build
 *     targets</a>
 */
public class BuckTarget {
  @Nullable private final String cellName;
  @Nullable private final String cellPath;
  private final String ruleName;

  // This regex from https://buckbuild.com/concept/build_target.html,
  // which says [A-Za-z0-9._-]*//[A-Za-z0-9/._-]*:[A-Za-z0-9_/.=,@~+-]+
  private static final Pattern PATTERN =
      Pattern.compile(
          "((@?(?<cell>[A-Za-z0-9._-]+))?//(?<path>[A-Za-z0-9/._-]*)?)?:(?<name>[A-Za-z0-9_/.=,@~+-]+)");

  /**
   * Parses the given string as a buck target, returning {@link Optional#empty()} if it is not a
   * syntactically valid buck target.
   *
   * <p>Note that no guarantee is given whether or not the target is *semantically* valid.
   */
  public static Optional<BuckTarget> parse(String s) {
    Matcher matcher = PATTERN.matcher(s);
    if (matcher.matches()) {
      @Nullable String cell = matcher.group("cell");
      @Nullable String path = matcher.group("path");
      String name = matcher.group("name");
      return Optional.of(new BuckTarget(cell, path, name));
    }
    return Optional.empty();
  }

  BuckTarget(@Nullable String cellName, @Nullable String cellPath, String ruleName) {
    if (cellName != null) {
      Preconditions.checkArgument(
          cellPath != null,
          "Cannot create BuckTarget with a non-null cellName and a null cellPath");
    }
    this.cellName = cellName;
    this.cellPath = cellPath;
    this.ruleName = ruleName;
  }

  /** Returns the name of the cell, or {@link Optional#empty()} if none was specified. */
  public Optional<String> getCellName() {
    return Optional.ofNullable(cellName);
  }

  /**
   * Returns the relative path from the cell's root, or {@link Optional#empty()} if it was
   * unspecified (for a package-relative buck target).
   */
  public Optional<String> getCellPath() {
    return Optional.ofNullable(cellPath);
  }

  /**
   * If this target has a {@link #getCellPath()}, returns an {@link Iterable} over the path segments
   * from the cell's root to its terminal package.
   */
  public Optional<Iterable<String>> getCellPathSegments() {
    return getCellPath().map(Splitter.on('/').omitEmptyStrings()::split);
  }

  /** Returns the rule name. */
  public String getRuleName() {
    return ruleName;
  }

  /** Returns true if this is a fully-qualified target (including a cell). */
  public boolean isAbsolute() {
    return cellName != null;
  }

  /** Returns true if this is a relative target to the same build file. */
  public boolean isPackageRelative() {
    return cellName == null && cellPath == null;
  }

  /**
   * Returns this target as a {@link BuckTargetPattern}.
   *
   * <p>Every {@link BuckTarget} is, by definition, a valid {@link BuckTargetPattern}.
   */
  public BuckTargetPattern asPattern() {
    Optional<BuckTargetPattern> optionalPattern = BuckTargetPattern.parse(toString());
    // Every buck target should also be a valid buck target pattern
    if (!optionalPattern.isPresent()) {
      throw new AssertionError(
          "Implementation error: the BuckTarget \""
              + toString()
              + "\" was unable to be parsed as a BuckTargetPattern");
    }
    return optionalPattern.get();
  }

  /**
   * Resolve the given buck target against this buck target.
   *
   * <p>Returns {@link Optional#empty()} if the "other" is not parsable as a relative target using
   * {@link #parse(String)}.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#resolve(String)}.
   */
  public Optional<BuckTarget> resolve(String other) {
    return parse(other).map(this::resolve);
  }

  /**
   * Resolve the given buck target against this target.
   *
   * <p>If this target is fully-qualified, the resulting target is also guaranteed to be fully
   * qualified.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#resolve(Path)}.
   */
  public BuckTarget resolve(BuckTarget other) {
    if (other.cellName != null) {
      return other; // no resolution necessary
    } else if (other.cellPath != null) {
      return new BuckTarget(cellName, other.cellPath, other.ruleName);
    } else {
      return new BuckTarget(cellName, cellPath, other.ruleName);
    }
  }

  /**
   * Constructs a relative path between this target and the given target.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#relativize(Path)}.
   */
  public BuckTarget relativize(BuckTarget other) {
    if (other.cellName != null && !other.cellName.equals(cellName)) {
      return other;
    } else if (other.cellPath != null && !other.cellPath.equals(cellPath)) {
      return new BuckTarget(null, other.cellPath, other.ruleName);
    } else {
      return new BuckTarget(null, null, other.ruleName);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BuckTarget that = (BuckTarget) o;
    return Objects.equal(cellName, that.cellName)
        && Objects.equal(cellPath, that.cellPath)
        && Objects.equal(ruleName, that.ruleName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(cellName, cellPath, ruleName);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    if (cellPath != null) {
      if (cellName != null) {
        sb.append(cellName);
      }
      sb.append("//").append(cellPath);
    }
    sb.append(":").append(ruleName);
    return sb.toString();
  }
}
