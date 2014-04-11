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

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class RuleUtilsTest {

  @Test
  public void extractGroupedSources() {
    ImmutableList.Builder<GroupedSource> sources = ImmutableList.builder();
    ImmutableMap.Builder<SourcePath, String> perFileCompileFlags = ImmutableMap.builder();

    ImmutableList<AppleSource> input = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("foo.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("bar.m"), "-Wall"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("baz.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("blech.m"), "-fobjc-arc"))))
        ));

    RuleUtils.extractSourcePaths(sources, perFileCompileFlags, input);
    assertEquals(
        ImmutableList.<GroupedSource>of(
            GroupedSource.ofSourceGroup(
                "Group1",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new TestSourcePath("foo.m")),
                    GroupedSource.ofSourcePath(new TestSourcePath("bar.m"))
                )),
            GroupedSource.ofSourceGroup(
                "Group2",
                ImmutableList.of(
                    GroupedSource.ofSourcePath(new TestSourcePath("baz.m")),
                    GroupedSource.ofSourcePath(new TestSourcePath("blech.m"))
                ))
    ), sources.build());
    assertEquals(ImmutableMap.<SourcePath, String>of(
        new TestSourcePath("bar.m"), "-Wall",
        new TestSourcePath("blech.m"), "-fobjc-arc"
    ), perFileCompileFlags.build());
  }
}
