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

package com.facebook.buck.core.parser.buildtargetpattern;

import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.base.Preconditions;
import org.immutables.value.Value;

/**
 * Parsed representation of build target pattern
 *
 * <p>Refer to {@link BuildTargetPatternParser} for acceptable pattern formats
 */
@BuckStylePrehashedValue
public abstract class BuildTargetPattern {

  /** Type of a pattern */
  public enum Kind {
    /** Pattern is a single build target, like cell//path/to/package:target */
    SINGLE {
      @Override
      public boolean isRecursive() {
        return false;
      }
    },
    /** Pattern matches all targets in one specific package, like cell//path/to/package: */
    PACKAGE {
      @Override
      public boolean isRecursive() {
        return false;
      }
    },
    /**
     * Pattern matches all targets in a package, and all packages below that in a directory tree,
     * i.e cell//path/to/package/...
     */
    RECURSIVE {
      @Override
      public boolean isRecursive() {
        return true;
      }
    };

    public abstract boolean isRecursive();
  }

  /** Path to the package folder that is a root for all build targets matched by a pattern */
  public abstract CellRelativePath getCellRelativeBasePath();

  /** Type of the parsed pattern */
  public abstract Kind getKind();

  /** Target name in case pattern is single build target pattern; otherwise an empty string */
  public abstract String getLocalNameAndFlavors();

  /** Whether this is a '//package/...' pattern. */
  public boolean isRecursive() {
    return getKind().isRecursive();
  }

  /**
   * Validate that target name is only present when necessary
   *
   * <p>Should we move it to factory {@link BuildTargetPatternParser}?
   */
  @Value.Check
  protected void check() {
    switch (getKind()) {
      case SINGLE:
        Preconditions.checkArgument(!getLocalNameAndFlavors().equals(""));
        Preconditions.checkArgument(!getLocalNameAndFlavors().startsWith("#"));
        break;
      case PACKAGE:
      case RECURSIVE:
        Preconditions.checkArgument(getLocalNameAndFlavors().equals(""));
        break;
    }
  }

  @Override
  public String toString() {
    String result = getCellRelativeBasePath().toString();
    switch (getKind()) {
      case SINGLE:
        return result + BuildTargetLanguageConstants.TARGET_SYMBOL + getLocalNameAndFlavors();
      case PACKAGE:
        return result + BuildTargetLanguageConstants.TARGET_SYMBOL;
      case RECURSIVE:
        return result
            + BuildTargetLanguageConstants.PATH_SYMBOL
            + BuildTargetLanguageConstants.RECURSIVE_SYMBOL;
    }
    throw new IllegalStateException();
  }

  public static BuildTargetPattern of(
      CellRelativePath cellRelativeBasePath, Kind kind, String localNameAndFlavors) {
    return ImmutableBuildTargetPattern.of(cellRelativeBasePath, kind, localNameAndFlavors);
  }
}
