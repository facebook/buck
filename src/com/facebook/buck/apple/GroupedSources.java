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
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

public class GroupedSources {
  // Utility class. Do not instantiate.
  private GroupedSources() { }

  /**
   * Returns an infix-order traversal of the {@link SourcePath}s of
   * each {@link GroupedSource} and its children in the input list.
   */
  public static ImmutableCollection<SourcePath> sourcePaths(
      Iterable<GroupedSource> groupedSources) {
    ImmutableList.Builder<SourcePath> sourcePathsBuilder = ImmutableList.builder();
    for (GroupedSource groupedSource : groupedSources) {
      addSourcePathsToBuilder(sourcePathsBuilder, groupedSource);
    }
    return sourcePathsBuilder.build();
  }

  private static void addSourcePathsToBuilder(
      ImmutableList.Builder<SourcePath> sourcePathsBuilder,
      GroupedSource groupedSource) {
    switch (groupedSource.getType()) {
      case SOURCE_PATH:
        sourcePathsBuilder.add(groupedSource.getSourcePath().get());
        break;
      case SOURCE_GROUP:
        for (GroupedSource sourceGroupEntry : groupedSource.getSourceGroup().get()) {
          addSourcePathsToBuilder(sourcePathsBuilder, sourceGroupEntry);
        }
        break;
      default:
        throw new IllegalStateException("Unhandled type: " + groupedSource.getType());
    }
  }
}
