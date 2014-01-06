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
import com.facebook.buck.rules.coercer.Either;
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

    ImmutableList<Either<SourcePath, Pair<SourcePath, String>>> input = ImmutableList.of(
        newEntry("foo.m"),
        newEntry("bar.m", "-flag1 -flag2"),
        newEntry("baz.m"),
        newEntry("quox.m", "-flag1"));

    RuleUtils.extractSourcePaths(files, perFileCompileFlags, input);
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

  private Either<SourcePath, Pair<SourcePath, String>> newEntry(String path) {
    return Either.ofLeft((SourcePath) new FileSourcePath(path));
  }

  private Either<SourcePath, Pair<SourcePath, String>> newEntry(String path, String flags) {
    return Either.ofRight(new Pair<SourcePath, String>(new FileSourcePath(path), flags));
  }
}
