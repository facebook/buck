/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.hashing;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

/** Unit tests for {@link PathHashing}. */
public class PathHashingTest {

  private ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private ProjectFileHashCache fileHashCache =
      FakeProjectFileHashCache.createFromStrings(
          projectFilesystem,
          ImmutableMap.of(
              "foo/foo.txt", "abcdef",
              "foo/bar.txt", "abcdef",
              "foo/baz.txt", "abcdef"));
  private ProjectFileHashCache modifiedFileHashCache =
      FakeProjectFileHashCache.createFromStrings(
          projectFilesystem,
          ImmutableMap.of(
              "foo/foo.txt", "123456",
              "foo/bar.txt", "123456",
              "foo/baz.txt", "123456"));

  @Test
  public void sameContentsSameNameHaveSameHash() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/bar.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), equalTo(hasher2.hash()));
  }

  @Test
  public void sameContentsDifferentNameHaveDifferentHashes() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/baz.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), not(equalTo(hasher2.hash())));
  }

  @Test
  public void sameNameDifferentContentsHaveDifferentHashes() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/bar.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, modifiedFileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), not(equalTo(hasher2.hash())));
  }

  @Test
  public void hashDoesNotDependOnFilesystemIterationOrder() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/foo.txt"));
    filesystem1.touch(Paths.get("foo/bar.txt"));
    filesystem1.touch(Paths.get("foo/baz.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/bar.txt"));
    filesystem2.touch(Paths.get("foo/baz.txt"));
    filesystem2.touch(Paths.get("foo/foo.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), equalTo(hasher2.hash()));
  }
}
