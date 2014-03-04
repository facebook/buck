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

import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

public class RuleUtilsTest {

  @Test
  public void extractSourcePaths() {
    ImmutableSortedSet.Builder<SourcePath> files = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();
    ImmutableList.Builder<GroupedSource> groupedSources = ImmutableList.builder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourcePath(
            new FileSourcePath("foo.m")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new FileSourcePath("bar.m"), "-flag1 -flag2")),
        AppleSource.ofSourcePath(
            new FileSourcePath("baz.m")),
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new FileSourcePath("quox.m"), "-flag1")));

    RuleUtils.extractSourcePaths(files, perFileCompileFlags, groupedSources, input);
    assertEquals(ImmutableSortedSet.<SourcePath>of(
        new FileSourcePath("foo.m"),
        new FileSourcePath("bar.m"),
        new FileSourcePath("baz.m"),
        new FileSourcePath("quox.m")
    ), files.build());
    assertEquals(ImmutableMap.<SourcePath, String>of(
        new FileSourcePath("bar.m"), "-flag1 -flag2",
        new FileSourcePath("quox.m"), "-flag1"
    ), perFileCompileFlags.build());
  }

  @Test
  public void extractGroupedSources() {
    ImmutableSortedSet.Builder<SourcePath> files = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();
    ImmutableList.Builder<GroupedSource> groupedSources = ImmutableList.builder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("foo.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("bar.m"), "-Wall"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("baz.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("blech.m"), "-fobjc-arc"))))
        ));

    RuleUtils.extractSourcePaths(files, perFileCompileFlags, groupedSources, input);
    assertEquals(ImmutableSortedSet.<SourcePath>of(
        new FileSourcePath("foo.m"),
        new FileSourcePath("bar.m"),
        new FileSourcePath("baz.m"),
        new FileSourcePath("blech.m")
    ), files.build());
    assertEquals(
        ImmutableList.<GroupedSource>of(
            GroupedSource.ofSourceGroup(
                "Group1",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new FileSourcePath("foo.m")),
                    GroupedSource.ofSourcePath(new FileSourcePath("bar.m"))
                )),
            GroupedSource.ofSourceGroup(
                "Group2",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new FileSourcePath("baz.m")),
                    GroupedSource.ofSourcePath(new FileSourcePath("blech.m"))
                ))
    ), groupedSources.build());
    assertEquals(ImmutableMap.<SourcePath, String>of(
        new FileSourcePath("bar.m"), "-Wall",
        new FileSourcePath("blech.m"), "-fobjc-arc"
    ), perFileCompileFlags.build());
  }

  @Test
  public void extractHeaders() {
    ImmutableSortedSet.Builder<SourcePath> files = ImmutableSortedSet.naturalOrder();
    ImmutableMap.Builder<SourcePath, HeaderVisibility> perHeaderVisibility = ImmutableMap.builder();
    ImmutableList.Builder<GroupedSource> groupedHeaders = ImmutableList.builder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("foo.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("bar.h"), "public"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("baz.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("blech.h"), "private"))))
        ));

    RuleUtils.extractHeaderPaths(files, perHeaderVisibility, groupedHeaders, input);
    assertEquals(ImmutableSortedSet.<SourcePath>of(
        new FileSourcePath("foo.h"),
        new FileSourcePath("bar.h"),
        new FileSourcePath("baz.h"),
        new FileSourcePath("blech.h")
    ), files.build());
    assertEquals(
        ImmutableList.<GroupedSource>of(
            GroupedSource.ofSourceGroup(
                "Group1",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new FileSourcePath("foo.h")),
                    GroupedSource.ofSourcePath(new FileSourcePath("bar.h"))
                )),
            GroupedSource.ofSourceGroup(
                "Group2",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new FileSourcePath("baz.h")),
                    GroupedSource.ofSourcePath(new FileSourcePath("blech.h"))
                ))
    ), groupedHeaders.build());
    assertEquals(ImmutableMap.<SourcePath, HeaderVisibility>of(
        new FileSourcePath("bar.h"), HeaderVisibility.PUBLIC,
        new FileSourcePath("blech.h"), HeaderVisibility.PRIVATE
    ), perHeaderVisibility.build());
  }
}
