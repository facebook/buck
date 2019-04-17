/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.io.file.MorePaths;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Parsed representation of build target pattern */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class BuildTargetPatternData {

  /** Delimiter that splits cell name and the rest of the pattern */
  public static final String ROOT_SYMBOL = "//";
  /** Delimiter that splits path to a package root in the pattern */
  public static final char PATH_SYMBOL = '/';
  /** Delimiter that splits target name and the rest of the pattern */
  public static final char TARGET_SYMBOL = ':';
  /** Symbol that represents recursive pattern */
  public static final String RECURSIVE_SYMBOL = "...";

  /** Type of a pattern */
  public enum Kind {
    /** Pattern is a single build target, like cell//path/to/package:target */
    SINGLE,
    /** Pattern matches all targets in one specific package, like cell//path/to/package: */
    PACKAGE,
    /**
     * Pattern matches all targets in a package, and all packages below that in a directory tree,
     * i.e cell//path/to/package/...
     */
    RECURSIVE
  }

  /** Name of the cell that current pattern specifies targets in */
  @Value.Parameter
  public abstract String getCell();

  /** Type of the parsed pattern */
  @Value.Parameter
  public abstract Kind getKind();

  /**
   * Relative path to the package folder that is a root for all build targets matched by a pattern
   */
  @Value.Parameter
  public abstract Path getBasePath();

  /** Target name in case pattern is single build target pattern; otherwise an empty string */
  @Value.Parameter
  public abstract String getTargetName();

  /**
   * Validate that target name is only present when necessary
   *
   * <p>Should we move it to factory {@link BuildTargetPatternDataParser}?
   */
  @Value.Check
  protected void check() {
    switch (getKind()) {
      case SINGLE:
        Preconditions.checkArgument(!getTargetName().equals(""));
        break;
      case PACKAGE:
      case RECURSIVE:
        Preconditions.checkArgument(getTargetName().equals(""));
        break;
    }
  }

  @Override
  public String toString() {
    String result = getCell() + ROOT_SYMBOL + MorePaths.pathWithUnixSeparators(getBasePath());
    switch (getKind()) {
      case SINGLE:
        return result + TARGET_SYMBOL + getTargetName();
      case PACKAGE:
        return result + TARGET_SYMBOL;
      case RECURSIVE:
        return result + PATH_SYMBOL + RECURSIVE_SYMBOL;
    }
    throw new IllegalStateException();
  }
}
