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
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.SortedSet;

/**
 * Common conversion functions from raw Description Arg specifications.
 */
public class RuleUtils {

  /** Utility class: do not instantiate. */
  private RuleUtils() {}

  public static ImmutableList<GroupedSource> createGroupsFromSourcePaths(
      Function<SourcePath, Path> resolver,
      Iterable<SourceWithFlags> sources,
      Iterable<SourcePath> extraXcodeSources,
      Iterable<SourcePath> publicHeaders,
      Iterable<SourcePath> privateHeaders) {
    Path rootPath = Paths.get("root");

    ImmutableMultimap.Builder<Path, GroupedSource> entriesBuilder = ImmutableMultimap.builder();
    for (SourceWithFlags sourceWithFlags : sources) {
      Path path = rootPath.resolve(resolver.apply(sourceWithFlags.getSourcePath()));
      GroupedSource groupedSource = GroupedSource.ofSourceWithFlags(sourceWithFlags);
      entriesBuilder.put(Preconditions.checkNotNull(path.getParent()), groupedSource);
    }
    for (SourcePath sourcePath : extraXcodeSources) {
      Path path = rootPath.resolve(resolver.apply(sourcePath));
      GroupedSource groupedSource = GroupedSource.ofSourceWithFlags(SourceWithFlags.of(sourcePath));
      entriesBuilder.put(Preconditions.checkNotNull(path.getParent()), groupedSource);
    }
    for (SourcePath publicHeader : publicHeaders) {
      Path path = rootPath.resolve(resolver.apply(publicHeader));
      GroupedSource groupedSource = GroupedSource.ofPublicHeader(publicHeader);
      entriesBuilder.put(Preconditions.checkNotNull(path.getParent()), groupedSource);
    }
    for (SourcePath privateHeader : privateHeaders) {
      Path path = rootPath.resolve(resolver.apply(privateHeader));
      GroupedSource groupedSource = GroupedSource.ofPrivateHeader(privateHeader);
      entriesBuilder.put(Preconditions.checkNotNull(path.getParent()), groupedSource);
    }
    ImmutableMultimap<Path, GroupedSource> entries = entriesBuilder.build();

    ImmutableMultimap.Builder<Path, String> subgroupsBuilder = ImmutableMultimap.builder();
    for (Path groupPath : entries.keys()) {
      Path parent = groupPath.getParent();
      while (parent != null) {
        subgroupsBuilder.put(parent, groupPath.getFileName().toString());
        groupPath = parent;
        parent = groupPath.getParent();
      }
    }
    ImmutableMultimap<Path, String> subgroups = subgroupsBuilder.build();

    GroupedSourceNameComparator groupedSourceNameComparator =
        new GroupedSourceNameComparator(resolver);

    ImmutableList<GroupedSource> groupedSources =
        createGroupsFromEntryMaps(
            subgroups,
            entries,
            groupedSourceNameComparator,
            rootPath,
            rootPath);

    // Remove the longest common prefix from all paths.
    while (groupedSources.size() == 1 &&
        groupedSources.get(0).getType() == GroupedSource.Type.SOURCE_GROUP) {
      groupedSources = ImmutableList.copyOf(groupedSources.get(0).getSourceGroup().get());
    }

    return groupedSources;
  }

  static class GroupedSourceNameComparator implements Comparator<GroupedSource> {
    private final Function<SourcePath, Path> pathResolver;

    public GroupedSourceNameComparator(Function<SourcePath, Path> pathResolver) {
      this.pathResolver = pathResolver;
    }

    @Override
    public int compare(GroupedSource source1, GroupedSource source2) {
      String name1 = source1.getName(pathResolver);
      String name2 = source2.getName(pathResolver);
      return name1.compareTo(name2);
    }

  }

  @VisibleForTesting
  static ImmutableList<GroupedSource> createGroupsFromEntryMaps(
      Multimap<Path, String> subgroups,
      Multimap<Path, GroupedSource> entries,
      Comparator<GroupedSource> comparator,
      Path rootGroupPath,
      Path groupPath) {
    ImmutableList.Builder<GroupedSource> groupBuilder = ImmutableList.builder();

    for (String subgroupName : ImmutableSortedSet.copyOf(subgroups.get(groupPath))) {
      Path subgroupPath = groupPath.resolve(subgroupName);
      groupBuilder.add(
          GroupedSource.ofSourceGroup(
              subgroupName,
              subgroupPath.subpath(rootGroupPath.getNameCount(), subgroupPath.getNameCount()),
              createGroupsFromEntryMaps(
                  subgroups,
                  entries,
                  comparator,
                  rootGroupPath,
                  subgroupPath)));
    }

    SortedSet<GroupedSource> sortedEntries =
        ImmutableSortedSet.copyOf(comparator, entries.get(groupPath));
    for (GroupedSource groupedSource : sortedEntries) {
      groupBuilder.add(groupedSource);
    }

    return groupBuilder.build().asList();
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
