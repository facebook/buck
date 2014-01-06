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

package com.facebook.buck.apple;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Common conversion functions from raw Description Arg specifications.
 */
public class RuleUtils {
  /**
   * Extract the source paths and flags from the input list and populate the output collections.
   *
   * @param outputSources source file names will be added to this builder
   * @param outputPerFileCompileFlags per file compile flags will be added to this builder
   * @param items input list of path/path-flags
   */
  public static void extractSourcePaths(
      ImmutableSortedSet.Builder<SourcePath> outputSources,
      ImmutableMap.Builder<SourcePath, String> outputPerFileCompileFlags,
      ImmutableList<Either<SourcePath, Pair<SourcePath, String>>> items) {
    for (Either<SourcePath, Pair<SourcePath, String>> item : items) {
      if (item.isLeft()) {
        outputSources.add(item.getLeft());
      } else if (item.isRight()) {
        Pair<SourcePath, String> pair = item.getRight();
        outputSources.add(pair.getFirst());
        outputPerFileCompileFlags.put(pair.getFirst(), pair.getSecond());
      } else {
        throw new RuntimeException("Impossible: Either contains neither left nor right value.");
      }
    }
  }
}
