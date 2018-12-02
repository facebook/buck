/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.EnumSet;
import org.junit.Test;

public class GlobPatternMatcherTest {

  @Test
  public void matchesPathsUnderProvidedBasePath() {
    GlobPatternMatcher matcher = GlobPatternMatcher.of("foo/*");
    assertTrue(matcher.matches(Paths.get("foo").resolve("bar")));
  }

  @Test
  public void doesNotMatchPathsOutsideOfProvidedBasePath() {
    GlobPatternMatcher matcher = GlobPatternMatcher.of("foo/*");
    assertFalse(matcher.matches(Paths.get("not_relative_too_root")));
  }

  @Test
  public void usesWatchmanQueryToMatchProvidedBasePath() {
    GlobPatternMatcher matcher = GlobPatternMatcher.of("foo/*");
    assertEquals(
        matcher.toWatchmanMatchQuery(EnumSet.noneOf(Capability.class)),
        ImmutableList.of("match", "foo/*", "wholename", ImmutableMap.of("includedotfiles", true)));
  }

  @Test
  public void returnsAGlobWhenAskedForPathOrGlob() {
    GlobPatternMatcher matcher = GlobPatternMatcher.of("foo/*");
    assertEquals(matcher.getPathOrGlob(), "foo/*");
  }
}
