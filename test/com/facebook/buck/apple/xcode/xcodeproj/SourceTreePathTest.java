/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcodeproj;

import static org.junit.Assert.assertNotEquals;

import com.google.common.base.Optional;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * Tests for {@link SourceTreePath}.
 */
public class SourceTreePathTest {
  @Test
  public void sourceTreePathsWithDifferentPathsAreDifferent() {
    SourceTreePath path1 = new SourceTreePath(
        PBXReference.SourceTree.SOURCE_ROOT,
        Paths.get("foo/bar.c"),
        Optional.<String>absent());
    SourceTreePath path2 = new SourceTreePath(
        PBXReference.SourceTree.SOURCE_ROOT,
        Paths.get("foo/baz.c"),
        Optional.<String>absent());
    assertNotEquals(path1, path2);
  }
}
