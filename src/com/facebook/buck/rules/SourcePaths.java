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

package com.facebook.buck.rules;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Collection;

import javax.annotation.Nullable;

/**
 * Utilities for dealing with {@link SourcePath}s.
 */
public class SourcePaths {

  public static final Function<Path, SourcePath> TO_SOURCE_PATH =
      new Function<Path, SourcePath>() {
        @Override
        public SourcePath apply(Path input) {
          return new PathSourcePath(input);
        }
      };
  public static final Function<PathSourcePath, Path> TO_PATH_SOURCEPATH_REFERENCES =
      new Function<PathSourcePath, Path>() {
        @Override
        public Path apply(PathSourcePath input) {
          return input.asReference();
        }
      };
  public static final Function<SourcePath, Path> TO_PATH =
      new Function<SourcePath, Path>() {
        @Override
        public Path apply(SourcePath input) {
          return input.resolve();
        }
      };

  /** Utility class: do not instantiate. */
  private SourcePaths() {}

  /**
   * Takes an {@link Iterable} of {@link SourcePath} objects and filters those that are suitable to
   * be returned by {@link Buildable#getInputsToCompareToOutput()}.
   */
  public static Collection<Path> filterInputsToCompareToOutput(
      Iterable<? extends SourcePath> sources) {
    // Currently, the only implementation of SourcePath that should be included in the Iterable
    // returned by getInputsToCompareToOutput() is FileSourcePath, so it is safe to filter by that
    // and then use .asReference() to get its path.
    //
    // BuildRuleSourcePath should not be included in the output because it refers to a generated
    // file, and generated files are not hashed as part of a RuleKey.
    return FluentIterable.from(sources)
        .filter(PathSourcePath.class)
        .transform(TO_PATH_SOURCEPATH_REFERENCES)
        .toList();
  }

  public static Iterable<Path> toPaths(Iterable<SourcePath> sourcePaths) {
    Function<SourcePath, Path> transform = new Function<SourcePath, Path>() {
      @Override
      public Path apply(SourcePath sourcePath) {
        return sourcePath.resolve();
      }
    };
    return Iterables.transform(sourcePaths, transform);
  }

  public static ImmutableSortedSet<SourcePath> toSourcePathsSortedByNaturalOrder(
      @Nullable Iterable<Path> paths) {
    if (paths == null) {
      return ImmutableSortedSet.of();
    }

    return FluentIterable.from(paths)
        .transform(TO_SOURCE_PATH)
        .toSortedSet(Ordering.natural());
  }
}
