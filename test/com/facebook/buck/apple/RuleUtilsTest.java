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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class RuleUtilsTest {

  @Test
  public void extractGroupedSources() {
    ImmutableList<SourceWithFlags> input =
        ImmutableList.of(
            SourceWithFlags.of(FakeSourcePath.of("Group1/foo.m")),
            SourceWithFlags.of(FakeSourcePath.of("Group1/bar.m"), ImmutableList.of("-Wall")),
            SourceWithFlags.of(FakeSourcePath.of("Group2/baz.m")),
            SourceWithFlags.of(
                FakeSourcePath.of("Group2/blech.m"), ImmutableList.of("-fobjc-arc")));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> sources =
        RuleUtils.createGroupsFromSourcePaths(
            resolver::getRelativePath,
            input,
            /* extraXcodeSources */ ImmutableSortedSet.of(),
            /* extraXcodeFiles */ ImmutableSortedSet.of(),
            /* publicHeaders */ ImmutableSortedSet.of(),
            /* privateHeaders */ ImmutableSortedSet.of());
    assertEquals(
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "Group1",
                Paths.get("Group1"),
                ImmutableList.of(
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(
                            FakeSourcePath.of("Group1/bar.m"), ImmutableList.of("-Wall"))),
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(FakeSourcePath.of("Group1/foo.m"))))),
            GroupedSource.ofSourceGroup(
                "Group2",
                Paths.get("Group2"),
                ImmutableList.of(
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(FakeSourcePath.of("Group2/baz.m"))),
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(
                            FakeSourcePath.of("Group2/blech.m"),
                            ImmutableList.of("-fobjc-arc")))))),
        sources);
  }

  @Test
  public void creatingGroupsFromSourcePaths() {
    ImmutableList<SourcePath> input =
        ImmutableList.of(
            FakeSourcePath.of("File.h"),
            FakeSourcePath.of("Lib/Foo/File2.h"),
            FakeSourcePath.of("App/Foo/File.h"),
            FakeSourcePath.of("Lib/Bar/File1.h"),
            FakeSourcePath.of("App/File.h"),
            FakeSourcePath.of("Lib/Foo/File1.h"),
            FakeSourcePath.of("App/Foo/Bar/File.h"));

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "App",
                Paths.get("App"),
                ImmutableList.of(
                    GroupedSource.ofSourceGroup(
                        "Foo",
                        Paths.get("App/Foo"),
                        ImmutableList.of(
                            GroupedSource.ofSourceGroup(
                                "Bar",
                                Paths.get("App/Foo/Bar"),
                                ImmutableList.of(
                                    GroupedSource.ofPrivateHeader(
                                        FakeSourcePath.of("App/Foo/Bar/File.h")))),
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/Foo/File.h")))),
                    GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/File.h")))),
            GroupedSource.ofSourceGroup(
                "Lib",
                Paths.get("Lib"),
                ImmutableList.of(
                    GroupedSource.ofSourceGroup(
                        "Bar",
                        Paths.get("Lib/Bar"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))),
                    GroupedSource.ofSourceGroup(
                        "Foo",
                        Paths.get("Lib/Foo"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")),
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File2.h")))))),
            GroupedSource.ofPrivateHeader(FakeSourcePath.of("File.h")));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver::getRelativePath,
            ImmutableList.of(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            ImmutableList.of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSourcePathsRemovesLongestCommonPrefix() {
    ImmutableList<SourcePath> input =
        ImmutableList.of(
            FakeSourcePath.of("Lib/Foo/File1.h"),
            FakeSourcePath.of("Lib/Foo/File2.h"),
            FakeSourcePath.of("Lib/Bar/File1.h"));

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "Bar",
                Paths.get("Lib/Bar"),
                ImmutableList.of(
                    GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))),
            GroupedSource.ofSourceGroup(
                "Foo",
                Paths.get("Lib/Foo"),
                ImmutableList.of(
                    GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")),
                    GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File2.h")))));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver::getRelativePath,
            ImmutableList.of(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            ImmutableList.of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleSourcePath() {
    ImmutableList<SourcePath> input = ImmutableList.of(FakeSourcePath.of("Lib/Foo/File1.h"));

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver::getRelativePath,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromNoSourcePaths() {
    ImmutableList<GroupedSource> expected = ImmutableList.of();

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver::getRelativePath,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of());

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEntryMaps() {
    ImmutableMultimap<Path, String> subgroups =
        ImmutableMultimap.<Path, String>builder()
            .put(Paths.get("root/App/Foo"), "Bar")
            .put(Paths.get("root"), "Lib")
            .put(Paths.get("root/Lib"), "Bar")
            .put(Paths.get("root/App"), "Foo")
            .put(Paths.get("root"), "App")
            .put(Paths.get("root/Lib"), "Foo")
            .build();
    ImmutableMultimap<Path, GroupedSource> entries =
        ImmutableMultimap.<Path, GroupedSource>builder()
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File2.h")))
            .put(
                Paths.get("root/App/Foo"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/Foo/File.h")))
            .put(
                Paths.get("root/App"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/File.h")))
            .put(Paths.get("root"), GroupedSource.ofPrivateHeader(FakeSourcePath.of("File.h")))
            .put(
                Paths.get("root/Lib/Bar"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")))
            .put(
                Paths.get("root/App/Foo/Bar"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/Foo/Bar/File.h")))
            .build();

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "App",
                Paths.get("App"),
                ImmutableList.of(
                    GroupedSource.ofSourceGroup(
                        "Foo",
                        Paths.get("App/Foo"),
                        ImmutableList.of(
                            GroupedSource.ofSourceGroup(
                                "Bar",
                                Paths.get("App/Foo/Bar"),
                                ImmutableList.of(
                                    GroupedSource.ofPrivateHeader(
                                        FakeSourcePath.of("App/Foo/Bar/File.h")))),
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/Foo/File.h")))),
                    GroupedSource.ofPrivateHeader(FakeSourcePath.of("App/File.h")))),
            GroupedSource.ofSourceGroup(
                "Lib",
                Paths.get("Lib"),
                ImmutableList.of(
                    GroupedSource.ofSourceGroup(
                        "Bar",
                        Paths.get("Lib/Bar"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))),
                    GroupedSource.ofSourceGroup(
                        "Foo",
                        Paths.get("Lib/Foo"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")),
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File2.h")))))),
            GroupedSource.ofPrivateHeader(FakeSourcePath.of("File.h")));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromEntryMaps(
            subgroups,
            entries,
            new RuleUtils.GroupedSourceNameComparator(resolver::getRelativePath),
            Paths.get("root"),
            Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEntryMapsKeepsLongestCommonPrefix() {
    ImmutableMultimap<Path, String> subgroups =
        ImmutableMultimap.<Path, String>builder()
            .put(Paths.get("root"), "Lib")
            .put(Paths.get("root/Lib"), "Bar")
            .put(Paths.get("root/Lib"), "Foo")
            .build();
    ImmutableMultimap<Path, GroupedSource> entries =
        ImmutableMultimap.<Path, GroupedSource>builder()
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File2.h")))
            .put(
                Paths.get("root/Lib/Bar"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")))
            .build();

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "Lib",
                Paths.get("Lib"),
                ImmutableList.of(
                    GroupedSource.ofSourceGroup(
                        "Bar",
                        Paths.get("Lib/Bar"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Bar/File1.h")))),
                    GroupedSource.ofSourceGroup(
                        "Foo",
                        Paths.get("Lib/Foo"),
                        ImmutableList.of(
                            GroupedSource.ofPrivateHeader(FakeSourcePath.of("Lib/Foo/File1.h")),
                            GroupedSource.ofPrivateHeader(
                                FakeSourcePath.of("Lib/Foo/File2.h")))))));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromEntryMaps(
            subgroups,
            entries,
            new RuleUtils.GroupedSourceNameComparator(resolver::getRelativePath),
            Paths.get("root"),
            Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleFileEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, GroupedSource> entries =
        ImmutableMultimap.of(
            Paths.get("root"), GroupedSource.ofPrivateHeader(FakeSourcePath.of("File1.h")));

    ImmutableList<GroupedSource> expected =
        ImmutableList.of(GroupedSource.ofPrivateHeader(FakeSourcePath.of("File1.h")));

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromEntryMaps(
            subgroups,
            entries,
            new RuleUtils.GroupedSourceNameComparator(resolver::getRelativePath),
            Paths.get("root"),
            Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEmptyEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, GroupedSource> entries = ImmutableMultimap.of();

    ImmutableList<GroupedSource> expected = ImmutableList.of();

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromEntryMaps(
            subgroups,
            entries,
            new RuleUtils.GroupedSourceNameComparator(resolver::getRelativePath),
            Paths.get("root"),
            Paths.get("root"));

    assertEquals(expected, actual);
  }
}
