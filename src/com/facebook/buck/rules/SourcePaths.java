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
import com.google.common.collect.Ordering;

import java.nio.file.Path;

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
  public static final Function<BuildRule, SourcePath> TO_BUILD_TARGET_SOURCE_PATH =
      new Function<BuildRule, SourcePath>() {
        @Override
        public SourcePath apply(BuildRule input) {
          return new BuildTargetSourcePath(input.getBuildTarget());
        }
      };

  /** Utility class: do not instantiate. */
  private SourcePaths() {}

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
