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

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RuleUtilsTest {

  @Test
  public void extractGroupedSources() {
    ImmutableSortedSet.Builder<SourcePath> allSourcesBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();
    ImmutableSortedSet.Builder<SourcePath> sourcePaths = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> headerPaths = ImmutableSortedSet.naturalOrder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourcePath(new TestSourcePath("Group1/foo.m")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(
                new TestSourcePath("Group1/bar.m"),
                "-Wall")),
        AppleSource.ofSourcePath(new TestSourcePath("Group2/baz.m")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(
                new TestSourcePath("Group2/blech.m"), "-fobjc-arc")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    RuleUtils.extractSourcePaths(
        resolver,
        allSourcesBuilder,
        perFileCompileFlags,
        sourcePaths,
        headerPaths,
        input);
    ImmutableList<GroupedSource> sources = RuleUtils.createGroupsFromSourcePaths(
        resolver,
        allSourcesBuilder.build());
    assertEquals(
        ImmutableList.of(
            GroupedSource.ofSourceGroup(
                "Group1",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new TestSourcePath("Group1/bar.m")),
                    GroupedSource.ofSourcePath(new TestSourcePath("Group1/foo.m"))
                )),
            GroupedSource.ofSourceGroup(
                "Group2",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new TestSourcePath("Group2/baz.m")),
                    GroupedSource.ofSourcePath(new TestSourcePath("Group2/blech.m"))
                ))),
        sources);
    assertEquals(ImmutableMap.<SourcePath, String>of(
            new TestSourcePath("Group1/bar.m"), "-Wall",
            new TestSourcePath("Group2/blech.m"), "-fobjc-arc"),
        perFileCompileFlags.build());
  }

  @Test
  public void extractUngroupedHeadersAndSources() {
    ImmutableSortedSet.Builder<SourcePath> allSourcesBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();
    ImmutableSortedSet.Builder<SourcePath> sourcePaths = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> headerPaths = ImmutableSortedSet.naturalOrder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourcePath(new TestSourcePath("foo.m")),
        AppleSource.ofSourcePath(new TestSourcePath("bar.h")),
        AppleSource.ofSourcePath(new TestSourcePath("baz.mm")),
        AppleSource.ofSourcePath(new TestSourcePath("blech.hh")),
        AppleSource.ofSourcePath(new TestSourcePath("beeble.c")));

    RuleUtils.extractSourcePaths(
        new SourcePathResolver(new BuildRuleResolver()),
        allSourcesBuilder,
        perFileCompileFlags,
        sourcePaths,
        headerPaths,
        input);
    assertEquals(
        ImmutableSortedSet.of(
            new TestSourcePath("foo.m"),
            new TestSourcePath("baz.mm"),
            new TestSourcePath("beeble.c")),
        sourcePaths.build());
    assertEquals(
        ImmutableSortedSet.of(
            new TestSourcePath("bar.h"),
            new TestSourcePath("blech.hh")),
        headerPaths.build());
  }

  @Test
  public void extractGroupedHeadersAndSources() {
    ImmutableSortedSet.Builder<SourcePath> allSourcesBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();
    ImmutableSortedSet.Builder<SourcePath> sourcePaths = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> headerPaths = ImmutableSortedSet.naturalOrder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("bar.m"), "-Wall")),
        AppleSource.ofSourcePath(new TestSourcePath("baz.hh")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(
                new TestSourcePath("blech.mm"), "-fobjc-arc")));

    RuleUtils.extractSourcePaths(
        new SourcePathResolver(new BuildRuleResolver()),
        allSourcesBuilder,
        perFileCompileFlags,
        sourcePaths,
        headerPaths,
        input);
    assertEquals(
        ImmutableSortedSet.of(
            new TestSourcePath("bar.m"),
            new TestSourcePath("blech.mm")),
        sourcePaths.build());
    assertEquals(
        ImmutableSortedSet.of(
            new TestSourcePath("foo.h"),
            new TestSourcePath("baz.hh")),
        headerPaths.build());
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
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourceGroup(
                            "Bar",
                            ImmutableList.of(
                                ImmutableGroupedSource.ofSourcePath(
                                    new TestSourcePath("App/Foo/Bar/File.h")))),
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("App/Foo/File.h")))),
                ImmutableGroupedSource.ofSourcePath(
                    new TestSourcePath("App/File.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File2.h")))))),
        ImmutableGroupedSource.ofSourcePath(new TestSourcePath("File.h")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromSourcePaths(resolver, input);

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
            ImmutableList.of(
                ImmutableGroupedSource.ofSourcePath(
                    new TestSourcePath("Lib/Bar/File1.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Foo",
            ImmutableList.of(
                ImmutableGroupedSource.ofSourcePath(
                    new TestSourcePath("Lib/Foo/File1.h")),
                ImmutableGroupedSource.ofSourcePath(
                    new TestSourcePath("Lib/Foo/File2.h")))));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromSourcePaths(resolver, input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleSourcePath() {
    ImmutableList<SourcePath> input = ImmutableList.<SourcePath>of(
        new TestSourcePath("Lib/Foo/File1.h"));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourcePath(
            new TestSourcePath("Lib/Foo/File1.h")));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromSourcePaths(resolver, input);

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromNoSourcePaths() {
    ImmutableList<SourcePath> input = ImmutableList.of();

    ImmutableList<GroupedSource> expected = ImmutableList.of();

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromSourcePaths(resolver, input);

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
    ImmutableMultimap<Path, SourcePath> entries = ImmutableMultimap.<Path, SourcePath>builder()
        .put(Paths.get("root/Lib/Foo"), new TestSourcePath("Lib/Foo/File2.h"))
        .put(Paths.get("root/App/Foo"), new TestSourcePath("App/Foo/File.h"))
        .put(Paths.get("root/App"), new TestSourcePath("App/File.h"))
        .put(Paths.get("root"), new TestSourcePath("File.h"))
        .put(Paths.get("root/Lib/Bar"), new TestSourcePath("Lib/Bar/File1.h"))
        .put(Paths.get("root/Lib/Foo"), new TestSourcePath("Lib/Foo/File1.h"))
        .put(Paths.get("root/App/Foo/Bar"), new TestSourcePath("App/Foo/Bar/File.h"))
        .build();

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "App",
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourceGroup(
                            "Bar",
                            ImmutableList.of(
                                ImmutableGroupedSource.ofSourcePath(
                                    new TestSourcePath("App/Foo/Bar/File.h")))),
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("App/Foo/File.h")))),
                ImmutableGroupedSource.ofSourcePath(
                    new TestSourcePath("App/File.h")))),
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File2.h")))))),
        ImmutableGroupedSource.ofSourcePath(new TestSourcePath("File.h")));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
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
    ImmutableMultimap<Path, SourcePath> entries = ImmutableMultimap.<Path, SourcePath>builder()
        .put(Paths.get("root/Lib/Foo"), new TestSourcePath("Lib/Foo/File2.h"))
        .put(Paths.get("root/Lib/Bar"), new TestSourcePath("Lib/Bar/File1.h"))
        .put(Paths.get("root/Lib/Foo"), new TestSourcePath("Lib/Foo/File1.h"))
        .build();

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourceGroup(
            "Lib",
            ImmutableList.of(
                ImmutableGroupedSource.ofSourceGroup(
                    "Bar",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Bar/File1.h")))),
                ImmutableGroupedSource.ofSourceGroup(
                    "Foo",
                    ImmutableList.of(
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File1.h")),
                        ImmutableGroupedSource.ofSourcePath(
                            new TestSourcePath("Lib/Foo/File2.h")))))));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromSingleFileEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, SourcePath> entries = ImmutableMultimap.<Path, SourcePath>of(
        Paths.get("root"), new TestSourcePath("File1.h"));

    ImmutableList<GroupedSource> expected = ImmutableList.of(
        ImmutableGroupedSource.ofSourcePath(
            new TestSourcePath("File1.h")));

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        Paths.get("root"));

    assertEquals(expected, actual);
  }

  @Test
  public void creatingGroupsFromEmptyEntryMaps() {
    ImmutableMultimap<Path, String> subgroups = ImmutableMultimap.of();
    ImmutableMultimap<Path, SourcePath> entries = ImmutableMultimap.of();

    ImmutableList<GroupedSource> expected = ImmutableList.of();

    ImmutableList<GroupedSource> actual = RuleUtils.createGroupsFromEntryMaps(
        subgroups,
        entries,
        Paths.get("root"));

    assertEquals(expected, actual);
  }
}
