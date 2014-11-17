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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * Common conversion functions from raw Description Arg specifications.
 */
public class RuleUtils {

  /** Utility class: do not instantiate. */
  private RuleUtils() {}

  private static void addSourcePathToBuilders(
      SourcePathResolver resolver,
      SourcePath sourcePath,
      ImmutableList.Builder<GroupedSource> outputSources,
      ImmutableSortedSet.Builder<SourcePath> outputSourcePaths,
      ImmutableSortedSet.Builder<SourcePath> outputHeaderPaths) {
    if (resolver.isSourcePathExtensionInSet(
        sourcePath,
        FileExtensions.CLANG_SOURCES)) {
      outputSourcePaths.add(sourcePath);
    } else if (resolver.isSourcePathExtensionInSet(
        sourcePath,
        FileExtensions.CLANG_HEADERS)) {
      outputHeaderPaths.add(sourcePath);
    }
    outputSources.add(GroupedSource.ofSourcePath(sourcePath));
  }

  /**
   * Extract the source and header paths and flags from the input list
   * and populate the output collections.
   *
   * @param outputSources The ordered tree of sources, headers, and groups (as
   *        they should appear in a generated Xcode project) will be added to
   *        this builder.
   * @param outputPerFileFlags per file flags will be added to this builder
   * @param outputSourcePaths The ordered list of paths to (non-header) source code
   *        files, as determined by the file extensions in SOURCE_FILE_EXTENSIONS.
   * @param outputHeaderPaths The ordered list of paths to header files,
   *        as determined by the file extensions in HEADER_FILE_EXTENSIONS.
   * @param items input list of sources
   */
  public static void extractSourcePaths(
      SourcePathResolver resolver,
      ImmutableList.Builder<GroupedSource> outputSources,
      ImmutableMap.Builder<SourcePath, String> outputPerFileFlags,
      ImmutableSortedSet.Builder<SourcePath> outputSourcePaths,
      ImmutableSortedSet.Builder<SourcePath> outputHeaderPaths,
      Collection<AppleSource> items) {
    for (AppleSource item : items) {
      switch (item.getType()) {
        case SOURCE_PATH:
          addSourcePathToBuilders(
              resolver,
              item.getSourcePath(),
              outputSources,
              outputSourcePaths,
              outputHeaderPaths);
          break;
        case SOURCE_PATH_WITH_FLAGS:
          Pair<SourcePath, String> pair = item.getSourcePathWithFlags();
          addSourcePathToBuilders(
              resolver,
              pair.getFirst(),
              outputSources,
              outputSourcePaths,
              outputHeaderPaths);
          outputPerFileFlags.put(pair.getFirst(), pair.getSecond());
          break;
        case SOURCE_GROUP:
          Pair<String, ImmutableList<AppleSource>> sourceGroup = item.getSourceGroup();
          String sourceGroupName = sourceGroup.getFirst();
          ImmutableList<AppleSource> sourceGroupItems = sourceGroup.getSecond();
          ImmutableList.Builder<GroupedSource> nestedSourceGroups = ImmutableList.builder();
          extractSourcePaths(
              resolver,
              nestedSourceGroups,
              outputPerFileFlags,
              outputSourcePaths,
              outputHeaderPaths,
              sourceGroupItems);
          outputSources.add(
              GroupedSource.ofSourceGroup(sourceGroupName, nestedSourceGroups.build()));
          break;
        default:
          throw new RuntimeException("Unhandled AppleSource item type: " + item.getType());
      }
    }
  }

  public static Supplier<ImmutableCollection<Path>> subpathsOfPathsSupplier(
      final ProjectFilesystem projectFilesystem,
      final Set<Path> dirs) {
    return Suppliers.memoize(
        new Supplier<ImmutableCollection<Path>>() {
          @Override
          public ImmutableCollection<Path> get() {
            ImmutableSortedSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();
            for (Path dir : dirs) {
              try {
                paths.addAll(projectFilesystem.getFilesUnderPath(dir));
              } catch (IOException e) {
                throw new HumanReadableException(e, "Error traversing directory: %s.", dir);
              }
            }
            return paths.build();
          }
        });
  }
}
