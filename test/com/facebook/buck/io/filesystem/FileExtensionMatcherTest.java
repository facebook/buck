/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.filesystem;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.EnumSet;
import org.junit.Test;

public class FileExtensionMatcherTest {

  @Test
  public void matchesPathsWithMatchingExtension() {
    FileExtensionMatcher matcher = FileExtensionMatcher.of("cpp");
    assertTrue(matcher.matches(RelPath.get("foo.cpp")));
  }

  @Test
  public void doesNotMatchPathsWithADifferentExtension() {
    FileExtensionMatcher matcher = FileExtensionMatcher.of("cpp");
    assertFalse(matcher.matches(RelPath.get("foo.java")));
  }

  @Test
  public void usesWatchmanQueryToMatchPathsWithExtension() {
    FileExtensionMatcher matcher = FileExtensionMatcher.of("cpp");
    assertEquals(
        matcher.toWatchmanMatchQuery(EnumSet.noneOf(Capability.class)),
        ImmutableList.of(
            "match", "**/*.cpp", "wholename", ImmutableMap.of("includedotfiles", true)));
  }

  @Test
  public void returnsAGlobWhenAskedForPathOrGlob() {
    FileExtensionMatcher matcher = FileExtensionMatcher.of("cpp");
    PathMatcher.PathOrGlob pathOrGlob = matcher.getPathOrGlob();
    assertTrue(pathOrGlob.isGlob());
    assertThat(pathOrGlob.getValue(), equalTo("**/*.cpp"));
  }
}
