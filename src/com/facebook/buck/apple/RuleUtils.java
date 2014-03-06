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
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Common conversion functions from raw Description Arg specifications.
 */
public class RuleUtils {

  /** Utility class: do not instantiate. */
  private RuleUtils() {}

  /**
   * Extract the source and header paths and flags from the input list
   * and populate the output collections.
   *
   * @param outputSources The ordered tree of sources, headers, and groups (as
   *        they should appear in a generated Xcode project) will be added to
   *        this builder.
   * @param outputPerFileFlags per file flags will be added to this builder
   * @param items input list of sources
   */
  public static void extractSourcePaths(
      ImmutableList.Builder<GroupedSource> outputSources,
      ImmutableMap.Builder<SourcePath, String> outputPerFileFlags,
      ImmutableList<AppleSource> items) {
    for (AppleSource item : items) {
      switch (item.getType()) {
        case SOURCE_PATH:
          outputSources.add(GroupedSource.ofSourcePath(item.getSourcePath()));
          break;
        case SOURCE_PATH_WITH_FLAGS:
          Pair<SourcePath, String> pair = item.getSourcePathWithFlags();
          outputSources.add(GroupedSource.ofSourcePath(pair.getFirst()));
          outputPerFileFlags.put(pair.getFirst(), pair.getSecond());
          break;
        case SOURCE_GROUP:
          Pair<String, ImmutableList<AppleSource>> sourceGroup = item.getSourceGroup();
          String sourceGroupName = sourceGroup.getFirst();
          ImmutableList<AppleSource> sourceGroupItems = sourceGroup.getSecond();
          ImmutableList.Builder<GroupedSource> nestedSourceGroups = ImmutableList.builder();
          extractSourcePaths(
              nestedSourceGroups,
              outputPerFileFlags,
              sourceGroupItems);
          outputSources.add(
              GroupedSource.ofSourceGroup(sourceGroupName, nestedSourceGroups.build()));
          break;
        default:
          throw new RuntimeException("Unhandled AppleSource item type: " + item.getType());
      }
    }
  }
}
