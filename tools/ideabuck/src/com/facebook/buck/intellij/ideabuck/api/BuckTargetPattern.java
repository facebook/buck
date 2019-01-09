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
 * Syntactic representation of a build pattern, which may specify one or more {@link BuckTarget}s.
 *
 * @see <a href=https://buckbuild.com/concept/build_target_pattern.html">the Buck documentation for
 *     build target patterns</a>
 */
public class BuckTargetPattern {

  @Nullable private final String cellName;
  @Nullable private final String cellPath;
  @Nullable private final String suffix;
  @Nullable private final String ruleName;

  // Based on regex from https://buckbuild.com/concept/build_target.html,
  // which says [A-Za-z0-9._-]* //[A-Za-z0-9/._-]*:[A-Za-z0-9_/.=,@~+-]+
  // Should also match variations that aren't valid BuckTargets, but are valid target patterns.
  //
  // cell//normal:target        => all valid BuckTargets are valid BuckTargetPatterns
  // cell//path/to/pkg          => implies the target cell//path/to/pkg:pkg
  // cell//path/to/pkg:         => implies all targets in /path/to/pkg/BUCK
  // cell//recursive/...        => implies all targets in recursive and its subdirs
  private static final Pattern PATTERN =
      Pattern.compile(
          "((@?(?<cell>[A-Za-z0-9._-]+))?//(?<path>[A-Za-z0-9/._-]*)?)?(?<suffix>:[A-Za-z0-9_/.=,@~+-]*)?");

  /**
   * Parses the given string as a buck target, returning {@link Optional#empty()} if it is not a
   * syntactically valid buck target.
   *
   * <p>Note that no guarantee is given whether or not the target is *semantically* valid.
   */
  public static Optional<BuckTargetPattern> parse(String raw) {
    return Optional.of(raw)
        .filter(s -> !s.isEmpty())
        .map(PATTERN::matcher)
        .filter(Matcher::matches)
        .map(
            matcher -> {
              @Nullable String cell = matcher.group("cell");
              @Nullable String path = matcher.group("path");
              @Nullable String suffix = matcher.group("suffix");
              if (path != null && suffix == null) {
                // Check for recursive pattern
                if ("...".equals(path)) {
                  path = "";
                  suffix = "/...";
                } else if (path.endsWith("/...")) {
                  path = path.substring(0, path.length() - 4);
                  suffix = "/...";
                }
              }
              return new BuckTargetPattern(cell, path, suffix);
            });
  }

  BuckTargetPattern(@Nullable String cellName, @Nullable String cellPath, @Nullable String suffix) {
    if (cellName != null) {
      Preconditions.checkArgument(
          cellPath != null,
          "Cannot create BuckTargetPattern with a non-null cellName and a null cellPath");
    }
    if (cellPath == null) {
      Preconditions.checkArgument(
          suffix != null, "Cannot create BuckTargetPattern with a null cellPath and a null suffix");
    }
    if (suffix != null) {
      Preconditions.checkArgument(suffix.startsWith(":") || "/...".equals(suffix));
    }
    this.cellName = cellName;
    this.cellPath = cellPath;
    this.suffix = suffix;
    if (suffix == null) {
      String lastSegmentOfPath = cellPath.substring(cellPath.lastIndexOf('/') + 1);
      ruleName = "".equals(lastSegmentOfPath) ? null : lastSegmentOfPath;
    } else if (suffix.startsWith(":") && !suffix.equals(":")) {
      ruleName = suffix.substring(1);
    } else {
      ruleName = null;
    }
  }

  /**
   * Returns a base BuckTargetPattern for a cell with the given name, suitable for use in referring
   * to all the targets in that cell or for resolving cell-relative targets.
   */
  public static BuckTargetPattern forCellName(@Nullable String cellName) {
    return new BuckTargetPattern(cellName, "", "/...");
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
   * If this pattern has a {@link #getCellPath()}, returns an {@link Iterable} over the path
   * segments from the cell's root to its terminal package.
   */
  public Optional<Iterable<String>> getCellPathSegments() {
    return getCellPath().map(Splitter.on('/').omitEmptyStrings()::split);
  }

  /**
   * Returns the suffix, which is either {@link Optional#empty()} or an {@link Optional} of:
   *
   * <ul>
   *   <li>A target, in the form {@code ":targetname"}
   *   <li>A package wildcard: {@code ":"}
   *   <li>A recursive subdirectory wildcard: {@code "/..."}
   * </ul>
   */
  public Optional<String> getSuffix() {
    return Optional.ofNullable(suffix);
  }

  /** Return a rule name if the pattern implicitly or explicit identifies one. */
  public Optional<String> getRuleName() {
    return Optional.ofNullable(ruleName);
  }

  /**
   * If this pattern happens to identify a specific buck target, return that target, else {@link
   * Optional#empty()}.
   *
   * <p>Note that this allows the pattern {@code "cell//with/path"}, which is not normally a valid
   * pattern, to be interpreted as the target {@code "cell//with/path:path"}.
   */
  public Optional<BuckTarget> asBuckTarget() {
    if (ruleName == null) {
      return Optional.empty();
    }
    return Optional.of(new BuckTarget(cellName, cellPath, ruleName));
  }

  /**
   * Resolve the given buck target pattern against this buck target pattern.
   *
   * <p>Returns {@link Optional#empty()} if the "other" is not parsable as a relative target using
   * {@link #parse(String)}.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#resolve(String)}.
   */
  public Optional<BuckTargetPattern> resolve(String other) {
    return parse(other).map(this::resolve);
  }

  /**
   * Resolve the given buck target against this buck target pattern.
   *
   * <p>If this target pattern is fully-qualified, the resulting target is also guaranteed to be
   * fully qualified.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#resolve(Path)}.
   */
  public BuckTarget resolve(BuckTarget other) {
    return new BuckTarget(
        other.getCellName().orElse(cellName),
        other.getCellPath().orElse(cellPath),
        other.getRuleName());
  }

  /**
   * Resolve the given buck target pattern against this target pattern.
   *
   * <p>If this target pattern is fully-qualified, the resulting target pattern is also guaranteed
   * to be fully qualified.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#resolve(Path)}.
   */
  public BuckTargetPattern resolve(BuckTargetPattern other) {
    if (other.cellName != null) {
      return other; // no resolution necessary
    } else if (other.cellPath != null) {
      return new BuckTargetPattern(cellName, other.cellPath, other.suffix);
    } else {
      return new BuckTargetPattern(cellName, cellPath, other.suffix);
    }
  }

  /**
   * Constructs a relative path between this target and the given target.
   *
   * <p>This is semantically similar to {@link java.nio.file.Path#relativize(Path)}.
   */
  public BuckTargetPattern relativize(BuckTargetPattern other) {
    if (other.cellName != null && !other.cellName.equals(cellName)) {
      return other;
    } else if (other.cellPath != null && !other.cellPath.equals(cellPath)) {
      return new BuckTargetPattern(null, other.cellPath, other.suffix);
    } else if (other.suffix == null) {
      if (ruleName != null) {
        // Must have been an implied rulename that wasn't in suffix, so make it explicit
        return new BuckTargetPattern(null, null, ":" + other.ruleName);
      }
      throw new AssertionError("Should be unreachable");
    } else if ("/...".equals(other.suffix)) {
      // If pattern is //path/..., then have to include a path
      return new BuckTargetPattern(null, other.cellPath, other.suffix);
    }
    return new BuckTargetPattern(null, null, other.suffix);
  }

  /**
   * Returns true if the given target is matched by this target pattern.
   *
   * <p>Usage note: the other pattern will be treated as *relative* to this pattern, and first
   * resolved against this pattern.
   */
  public boolean matches(BuckTarget target) {
    return matches(target.asPattern());
  }
  /**
   * Returns true if every target that could possibly match the other target pattern would also be
   * matched by this target pattern.
   *
   * <p>Usage note: the other pattern will be treated as a *relative* pattern to this pattern.
   */
  public boolean matches(BuckTargetPattern other) {
    if (!Objects.equal(this.cellName, other.cellName)) {
      return false;
    }
    if (Objects.equal(this.cellPath, other.cellPath)) {
      if (this.isRecursivePackageMatching()) {
        return true;
      } else if (other.isRecursivePackageMatching()) {
        return false;
      } else if (this.isPackageMatching()) {
        return true;
      } else if (other.isPackageMatching()) {
        return false;
      } else {
        return Objects.equal(this.ruleName, other.ruleName);
      }
    } else if (this.cellPath == null || other.cellPath == null) {
      return false;
    } else {
      return this.isRecursivePackageMatching()
          && ("".equals(this.cellPath) || other.cellPath.startsWith(this.cellPath + "/"));
    }
  }

  /** Returns true if this pattern is fully specified (i.e., has a cell). */
  public boolean isAbsolute() {
    return cellName != null;
  }

  /** Returns true if this pattern is relative to the current package). */
  public boolean isPackageRelative() {
    return cellName == null && cellPath == null && suffix.startsWith(":");
  }

  /**
   * Returns true if this pattern is a wildcard that matches all targets in a package (i.e., a
   * trailing {@code ":"}).
   */
  public boolean isPackageMatching() {
    return ":".equals(suffix);
  }

  /** Returns a pattern like this pattern, but matching all targets in the given package. */
  public BuckTargetPattern asPackageMatchingPattern() {
    return new BuckTargetPattern(cellName, cellPath, ":");
  }

  /**
   * Returns true if this pattern is a wildcard that matches all targets in both a package and its
   * recursive subpackages (i.e., a pattern ending in {@code "/..."}).
   */
  public boolean isRecursivePackageMatching() {
    return "/...".equals(suffix);
  }

  /**
   * Returns a pattern like this pattern, but matching all targets in the given package and its
   * recursive subpackages.
   */
  public BuckTargetPattern asRecursivePackageMatchingPattern() {
    return new BuckTargetPattern(cellName, cellPath, "/...");
  }

  /**
   * Returns a syntatically valid (but semantically different) variation of this target pattern,
   * with hierarchical path elements in the rule name moved to the path. Example, {@code
   * foo//bar:baz/qux => foo//bar/baz:qux}.
   *
   * @see {@link BuckTarget#flatten()} for more details.
   */
  public Optional<BuckTargetPattern> flatten() {
    if (suffix == null || !suffix.contains("/") || isRecursivePackageMatching()) {
      return Optional.of(this);
    }
    return asBuckTarget().flatMap(BuckTarget::flatten).map(BuckTarget::asPattern);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BuckTargetPattern that = (BuckTargetPattern) o;
    return Objects.equal(cellName, that.cellName)
        && Objects.equal(cellPath, that.cellPath)
        && Objects.equal(suffix, that.suffix)
        && Objects.equal(ruleName, that.ruleName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(cellName, cellPath, suffix, ruleName);
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
    if (suffix != null) {
      if ("/...".equals(suffix) && cellPath != null && cellPath.isEmpty()) {
        // Special case to avoid "cell///..." when cellPath is empty.
        sb.append("...");
      } else {
        sb.append(suffix);
      }
    }
    return sb.toString();
  }
}
