/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.filesystem;

import com.facebook.buck.util.environment.Platform;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Test;

public class PathFragmentsTest {

  // Note: since PathFragment conversion functions detects the current platform, we can only test
  // Windows specific paths on Windows, and *nix on *nix.

  @Test
  public void propertyFragmentToPathToFragmentReturnsEquivalentFragment() {
    Consumer<PathFragment> check =
        x -> Assert.assertEquals(x, PathFragments.pathToFragment(PathFragments.fragmentToPath(x)));
    check.accept(PathFragment.create(""));
    check.accept(PathFragment.create("foo"));
    check.accept(PathFragment.create("foo/bar"));
    check.accept(PathFragment.create("foo//bar"));

    if (Platform.detect() == Platform.WINDOWS) {
      check.accept(PathFragment.create("c:\\"));
      check.accept(PathFragment.create("c:\\foo\\bar"));
    } else {
      check.accept(PathFragment.create("/"));
      check.accept(PathFragment.create("/foo/bar"));
    }
  }

  @Test
  public void propertyPathToFragmentToPathReturnsEquivalentPath() {
    Consumer<Path> check =
        x -> Assert.assertEquals(x, PathFragments.fragmentToPath(PathFragments.pathToFragment(x)));
    check.accept(Paths.get(""));
    check.accept(Paths.get("foo"));
    check.accept(Paths.get("foo/bar"));
    check.accept(Paths.get("foo//bar"));

    if (Platform.detect() == Platform.WINDOWS) {
      check.accept(Paths.get("c:\\"));
      check.accept(Paths.get("c:\\foo\\bar"));
    } else {
      check.accept(Paths.get("/"));
      check.accept(Paths.get("/foo/bar"));
    }
  }
}
