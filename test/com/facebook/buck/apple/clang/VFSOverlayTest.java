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

package com.facebook.buck.apple.clang;

import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import org.junit.Assume;
import org.junit.Test;

public class VFSOverlayTest {

  private String readTestData(String name) throws IOException {
    return new String(ByteStreams.toByteArray(getClass().getResourceAsStream(name)));
  }

  @Test
  public void testSerialization() throws IOException {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                MorePathsForTests.rootRelativePath("virtual/path/module.modulemap"),
                MorePathsForTests.rootRelativePath("real/path/overlayed.modulemap")));
    assertThat(
        readTestData("testdata/vfs_simple.yaml"),
        equalToIgnoringPlatformNewlines(vfsOverlay.render()));
  }

  @Test
  public void testSerializationWindows() throws IOException {
    Assume.assumeTrue(Platform.detect() == Platform.WINDOWS);
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                MorePathsForTests.rootRelativePath("virtual/path/module.modulemap"),
                MorePathsForTests.rootRelativePath("real/path/overlayed.modulemap")));
    assertThat(
        readTestData("testdata/vfs_simple_windows.yaml"),
        equalToIgnoringPlatformNewlines(vfsOverlay.render()));
  }

  @Test
  public void testTwoFiles() throws IOException {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                MorePathsForTests.rootRelativePath("virtual/path/module.modulemap"),
                MorePathsForTests.rootRelativePath("real/path/overlayed.modulemap"),
                MorePathsForTests.rootRelativePath("virtual/path/umbrella.h"),
                MorePathsForTests.rootRelativePath("real/path/umbrella/umbrella.h")));
    assertThat(
        readTestData("testdata/vfs_twofiles.yaml"),
        equalToIgnoringPlatformNewlines(vfsOverlay.render()));
  }

  @Test
  public void testTwoDirectories() throws IOException {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                MorePathsForTests.rootRelativePath("virtual/path/module.modulemap"),
                MorePathsForTests.rootRelativePath("real/path/overlayed.modulemap"),
                MorePathsForTests.rootRelativePath("virtual/path-priv/umbrella.h"),
                MorePathsForTests.rootRelativePath("real/path/umbrella/umbrella.h")));
    assertThat(
        readTestData("testdata/vfs_twodirs.yaml"),
        equalToIgnoringPlatformNewlines(vfsOverlay.render()));
  }

  @Test
  public void testNestedDirectories() throws IOException {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    // the default clang writer groups nested directories, for simplicity this generator doesn't.
    // This test shows the expectation for this generator, we can update it if we implement
    // directory nesting/grouping. Clang has an internal representation after reading the vfs so as
    // long as the contents of the vfs are correct the layout is not relevant for speed/correctness.
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                MorePathsForTests.rootRelativePath("virtual/module.modulemap"),
                MorePathsForTests.rootRelativePath("real/path/overlayed.modulemap"),
                MorePathsForTests.rootRelativePath("virtual/path/priv/umbrella.h"),
                MorePathsForTests.rootRelativePath("real/path/umbrella/umbrella.h"),
                MorePathsForTests.rootRelativePath("virtual/path/priv/entry.h"),
                MorePathsForTests.rootRelativePath("real/path/umbrella/lib/entry.h")));
    assertThat(
        readTestData("testdata/vfs_nesteddirs.yaml"),
        equalToIgnoringPlatformNewlines(vfsOverlay.render()));
  }
}
