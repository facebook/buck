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
import com.google.common.collect.ImmutableSortedSet;

/**
 * Common conversion functions from raw Description Arg specifications.
 */
public class RuleUtils {

  /** Utility class: do not instantiate. */
  private RuleUtils() {}

  /**
   * Extract the header paths and visibility flags from the input list
   * and populate the output collections.
   *
   * @param outputHeaders header file names will be added to this builder
   * @param outputPerFileHeaderVisibility per file visibility flags will be added to this builder
   *        (if not present, a header should use PROJECT visibility)
   * @param outputGroupedHeaders The ordered tree of headers and header groups (as
   *        they should appear in a generated Xcode project) will be added to
   *        this builder.
   * @param items input list of headers
   */
  public static void extractHeaderPaths(
      ImmutableSortedSet.Builder<SourcePath> outputHeaders,
      ImmutableMap.Builder<SourcePath, HeaderVisibility> outputPerFileHeaderVisibility,
      ImmutableList.Builder<GroupedSource> outputGroupedHeaders,
      ImmutableList<AppleSource> items) {
    ImmutableMap.Builder<SourcePath, String> perFileFlagsBuilder = ImmutableMap.builder();
    extractSourcePaths(
        outputHeaders,
        perFileFlagsBuilder,
        outputGroupedHeaders,
        items);
    for (ImmutableMap.Entry<SourcePath, String> entry : perFileFlagsBuilder.build().entrySet()) {
      outputPerFileHeaderVisibility.put(
          entry.getKey(),
          HeaderVisibility.fromString(entry.getValue()));
    }
  }

  /**
   * Extract the source paths and flags from the input list and populate the output collections.
   *
   * @param outputSources source file names will be added to this builder
   * @param outputPerFileCompileFlags per file compile flags will be added to this builder
   * @param outputGroupedSources The ordered tree of sources and source groups (as
   *        they should appear in a generated Xcode project) will be added to
   *        this builder.
   * @param items input list of sources
   */
  public static void extractSourcePaths(
      ImmutableSortedSet.Builder<SourcePath> outputSources,
      ImmutableMap.Builder<SourcePath, String> outputPerFileCompileFlags,
      ImmutableList.Builder<GroupedSource> outputGroupedSources,
      ImmutableList<AppleSource> items) {
    for (AppleSource item : items) {
      switch (item.getType()) {
        case SOURCE_PATH:
          outputSources.add(item.getSourcePath());
          outputGroupedSources.add(GroupedSource.ofSourcePath(item.getSourcePath()));
          break;
        case SOURCE_PATH_WITH_FLAGS:
          Pair<SourcePath, String> pair = item.getSourcePathWithFlags();
          outputSources.add(pair.getFirst());
          outputGroupedSources.add(GroupedSource.ofSourcePath(pair.getFirst()));
          outputPerFileCompileFlags.put(pair.getFirst(), pair.getSecond());
          break;
        case SOURCE_GROUP:
          Pair<String, ImmutableList<AppleSource>> sourceGroup = item.getSourceGroup();
          String sourceGroupName = sourceGroup.getFirst();
          ImmutableList<AppleSource> sourceGroupItems = sourceGroup.getSecond();
          ImmutableList.Builder<GroupedSource> nestedSourceGroups = ImmutableList.builder();
          extractSourcePaths(
              outputSources,
              outputPerFileCompileFlags,
              nestedSourceGroups,
              sourceGroupItems);
          outputGroupedSources.add(
              GroupedSource.ofSourceGroup(sourceGroupName, nestedSourceGroups.build()));
          break;
        default:
          throw new RuntimeException("Unhandled AppleSource item type: " + item.getType());
      }
    }
  }
}
