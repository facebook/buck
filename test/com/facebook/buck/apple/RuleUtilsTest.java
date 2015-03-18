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

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RuleUtilsTest {

  @Test
  public void extractGroupedSources() {
    ImmutableList<SourceWithFlags> input = ImmutableList.of(
        SourceWithFlags.of(new TestSourcePath("Group1/foo.m")),
        SourceWithFlags.of(
            new TestSourcePath("Group1/bar.m"),
            ImmutableList.of("-Wall")),
        SourceWithFlags.of(new TestSourcePath("Group2/baz.m")),
        SourceWithFlags.of(
            new TestSourcePath("Group2/blech.m"), ImmutableList.of("-fobjc-arc")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> sources = RuleUtils.createGroupsFromSourcePaths(
        resolver.getPathFunction(),
        input,
        /* extraXcodeSources */ ImmutableSortedSet.<SourcePath>of(),
        /* publicHeaders */ ImmutableSortedSet.<SourcePath>of(),
        /* privateHeaders */ ImmutableSortedSet.<SourcePath>of());
    assertEquals(
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "Group1",
                Paths.get("Group1"),
                ImmutableList.of(
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(
                            new TestSourcePath("Group1/bar.m"),
                            ImmutableList.of("-Wall"))),
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(new TestSourcePath("Group1/foo.m")))
                )),
            GroupedSource.ofSourceGroup(
                "Group2",
                Paths.get("Group2"),
                ImmutableList.of(
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(new TestSourcePath("Group2/baz.m"))),
                    GroupedSource.ofSourceWithFlags(
                        SourceWithFlags.of(
                            new TestSourcePath("Group2/blech.m"),
                            ImmutableList.of("-fobjc-arc")))
                ))),
        sources);
  }

  @Test
  public void creatingGroupsFromSourcePaths() {
    ImmutableList<SourcePath> input = ImmutableList.<SourcePath>of(
        new TestSourcePath("File.h"),
        new TestSourcePath("Lib/Foo/File2.h"),
        new TestSourcePath("App/Foo/File.h"),
        new TestSourcePath("Lib/Bar/File1.h"),
        new TestSourcePath("App/File.h"),
        new TestSourcePath("Lib/Foo/File1.h"),
        new TestSourcePath("App/Foo/Bar/File.h"));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "App",
            Paths.get("App"),
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    Paths.get("App/Foo"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourceGroup(
                            "Bar",
                            Paths.get("App/Foo/Bar"),
                            ImmutableList.of(
                                ImmutableGroupedSource.ofPrivateHeader(
                                    new TestSourcePath("App/Foo/Bar/File.h")))),
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("App/Foo/File.h")))),
                ImmutableGroupedSource.ofPrivateHeader(
                    new TestSourcePath("App/File.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            Paths.get("Lib"),
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    Paths.get("Lib/Bar"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    Paths.get("Lib/Foo"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File2.h")))))),
        ImmutableGroupedSource.ofPrivateHeader(new TestSourcePath("File.h")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver.getPathFunction(),
            ImmutableList.<SourceWithFlags>of(),
            ImmutableSortedSet.<SourcePath>of(),
            ImmutableList.<SourcePath>of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSourcePathsRemovesLongestCommonPrefix() {
    ImmutableList<SourcePath> input = ImmutableList.<SourcePath>of(
        new TestSourcePath("Lib/Foo/File1.h"),
        new TestSourcePath("Lib/Foo/File2.h"),
        new TestSourcePath("Lib/Bar/File1.h"));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "Bar",
            Paths.get("Lib/Bar"),
            ImmutableList.of(
                ImmutableGroupedSource.ofPrivateHeader(
                    new TestSourcePath("Lib/Bar/File1.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Foo",
            Paths.get("Lib/Foo"),
            ImmutableList.of(
                ImmutableGroupedSource.ofPrivateHeader(
                    new TestSourcePath("Lib/Foo/File1.h")),
                ImmutableGroupedSource.ofPrivateHeader(
                    new TestSourcePath("Lib/Foo/File2.h")))));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver.getPathFunction(),
            ImmutableList.<SourceWithFlags>of(),
            ImmutableSortedSet.<SourcePath>of(),
            ImmutableList.<SourcePath>of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleSourcePath() {
    ImmutableList<SourcePath> input = ImmutableList.<SourcePath>of(
        new TestSourcePath("Lib/Foo/File1.h"));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofPrivateHeader(
            new TestSourcePath("Lib/Foo/File1.h")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver.getPathFunction(),
            ImmutableList.<SourceWithFlags>of(),
            ImmutableList.<SourcePath>of(),
            ImmutableList.<SourcePath>of(),
            input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromNoSourcePaths() {
    ImmutableList<GroupedSource> expected = ImmutableList.of();

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual =
        RuleUtils.createGroupsFromSourcePaths(
            resolver.getPathFunction(),
            ImmutableList.<SourceWithFlags>of(),
            ImmutableList.<SourcePath>of(),
            ImmutableList.<SourcePath>of(),
            ImmutableList.<SourcePath>of());

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.<Path, String>builder()
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
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Foo/File2.h")))
            .put(
                Paths.get("root/App/Foo"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("App/Foo/File.h")))
            .put(
                Paths.get("root/App"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("App/File.h")))
            .put(
                Paths.get("root"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("File.h")))
            .put(
                Paths.get("root/Lib/Bar"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Bar/File1.h")))
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Foo/File1.h")))
            .put(
                Paths.get("root/App/Foo/Bar"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("App/Foo/Bar/File.h")))
            .build();

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "App",
            Paths.get("App"),
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    Paths.get("App/Foo"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourceGroup(
                            "Bar",
                            Paths.get("App/Foo/Bar"),
                            ImmutableList.of(
                                ImmutableGroupedSource.ofPrivateHeader(
                                    new TestSourcePath("App/Foo/Bar/File.h")))),
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("App/Foo/File.h")))),
                ImmutableGroupedSource.ofPrivateHeader(
                    new TestSourcePath("App/File.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            Paths.get("Lib"),
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    Paths.get("Lib/Bar"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    Paths.get("Lib/Foo"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File2.h")))))),
        ImmutableGroupedSource.ofPrivateHeader(new TestSourcePath("File.h")));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        new RuleUtils.GroupedSourceNameComparator(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction()),
        Paths.get("root"),
        Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEntryMapsKeepsLongestCommonPrefix() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.<Path, String>builder()
        .put(Paths.get("root"), "Lib")
        .put(Paths.get("root/Lib"), "Bar")
        .put(Paths.get("root/Lib"), "Foo")
        .build();
    ImmutableMultimap<Path, GroupedSource> entries =
        ImmutableMultimap.<Path, GroupedSource>builder()
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Foo/File2.h")))
            .put(
                Paths.get("root/Lib/Bar"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Bar/File1.h")))
            .put(
                Paths.get("root/Lib/Foo"),
                GroupedSource.ofPrivateHeader(new TestSourcePath("Lib/Foo/File1.h")))
            .build();

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            Paths.get("Lib"),
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    Paths.get("Lib/Bar"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    Paths.get("Lib/Foo"),
                    ImmutableList.of(
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofPrivateHeader(
                            new TestSourcePath("Lib/Foo/File2.h")))))));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        new RuleUtils.GroupedSourceNameComparator(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction()),
        Paths.get("root"),
        Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleFileEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, GroupedSource> entries = ImmutableMultimap.of(
        Paths.get("root"), GroupedSource.ofPrivateHeader(new TestSourcePath("File1.h")));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofPrivateHeader(
            new TestSourcePath("File1.h")));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        new RuleUtils.GroupedSourceNameComparator(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction()),
        Paths.get("root"),
        Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEmptyEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, GroupedSource> entries = ImmutableMultimap.of();

    ImmutableList<GroupedSource> expected = ImmutableList.of();

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        new RuleUtils.GroupedSourceNameComparator(
            new SourcePathResolver(new BuildRuleResolver()).getPathFunction()),
        Paths.get("root"),
        Paths.get("root"));

    assertEquals(expected, actual);
  }
}
