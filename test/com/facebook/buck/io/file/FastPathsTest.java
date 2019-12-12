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

package com.facebook.buck.io.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.filesystems.BuckUnixPath;
import com.facebook.buck.core.filesystems.BuckUnixPathUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class FastPathsTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testGetNameString() {
    BuckUnixPath unixPath = BuckUnixPathUtils.createPath("some/path/one/two/three");

    assertEquals("some", FastPaths.getNameString(unixPath, 0));
    assertEquals("path", FastPaths.getNameString(unixPath, 1));
    assertEquals("one", FastPaths.getNameString(unixPath, 2));
    assertEquals("two", FastPaths.getNameString(unixPath, 3));
    assertEquals("three", FastPaths.getNameString(unixPath, 4));

    // Make sure FastPaths.getNameString really doesn't allocate anything.
    BuckUnixPath otherPath = BuckUnixPathUtils.createPath("some/path/one");
    assertSame(FastPaths.getNameString(otherPath, 1), FastPaths.getNameString(unixPath, 1));
  }

  @Test
  public void testHashFast() {
    Assume.assumeTrue(
        "BuckUnixPath isn't used on Windows and so the behavior isn't quite the same as Path.",
        Platform.detect() != Platform.WINDOWS);

    String pathString = "some/path/one/two/three";
    BuckUnixPath unixPath = BuckUnixPathUtils.createPath(pathString);
    Hasher buckHasher = Hashing.sha1().newHasher();
    FastPaths.hashPathFast(buckHasher, unixPath);

    Path javaPath = BuckUnixPathUtils.createJavaPath(pathString);
    Hasher javaHasher = Hashing.sha1().newHasher();
    FastPaths.hashPathFast(javaHasher, javaPath);

    assertEquals(javaHasher.hash(), buckHasher.hash());
  }
}
