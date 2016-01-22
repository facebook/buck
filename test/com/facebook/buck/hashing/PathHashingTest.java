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

package com.facebook.buck.hashing;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit tests for {@link PathHashing}.
 */
public class PathHashingTest {
  ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  FileHashCache fileHashCache = new FakeFileHashCache(
      ImmutableMap.of(
          projectFilesystem.resolve("foo/foo.txt"), HashCode.fromString("abcdef"),
          projectFilesystem.resolve("foo/bar.txt"), HashCode.fromString("abcdef"),
          projectFilesystem.resolve("foo/baz.txt"), HashCode.fromString("abcdef")));

  FileHashCache modifiedFileHashCache = new FakeFileHashCache(
      ImmutableMap.of(
          projectFilesystem.resolve("foo/foo.txt"), HashCode.fromString("123456"),
          projectFilesystem.resolve("foo/bar.txt"), HashCode.fromString("123456"),
          projectFilesystem.resolve("foo/baz.txt"), HashCode.fromString("123456")));

  @Test
  public void emptyPathHasExpectedHash() throws IOException {
    Hasher hasher = Hashing.sha1().newHasher();
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem emptyFilesystem = new FakeProjectFilesystem(clock);
    PathHashing.hashPaths(
        hasher,
        new NullFileHashCache(),
        emptyFilesystem,
        ImmutableSortedSet.<Path>of());
    HashCode emptyStringHashCode = Hashing.sha1().newHasher().hash();
    assertThat(
        hasher.hash(),
        equalTo(emptyStringHashCode));
  }

  @Test
  public void sameContentsSameNameHaveSameHash() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/bar.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher1,
        fileHashCache,
        filesystem1,
        ImmutableSortedSet.of(Paths.get("foo")));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher2,
        fileHashCache,
        filesystem2,
        ImmutableSortedSet.of(Paths.get("foo")));

    assertThat(
        hasher1.hash(),
        equalTo(hasher2.hash()));
  }

  @Test
  public void sameContentsDifferentNameHaveDifferentHashes() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/baz.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher1,
        fileHashCache,
        filesystem1,
        ImmutableSortedSet.of(Paths.get("foo")));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher2,
        fileHashCache,
        filesystem2,
        ImmutableSortedSet.of(Paths.get("foo")));

    assertThat(
        hasher1.hash(),
        not(equalTo(hasher2.hash())));
  }

  @Test
  public void sameNameDifferentContentsHaveDifferentHashes() throws IOException {
    SettableFakeClock clock = new SettableFakeClock(1000, 0);
    FakeProjectFilesystem filesystem1 = new FakeProjectFilesystem(clock);
    filesystem1.touch(Paths.get("foo/bar.txt"));

    FakeProjectFilesystem filesystem2 = new FakeProjectFilesystem(clock);
    filesystem2.touch(Paths.get("foo/bar.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher1,
        fileHashCache,
        filesystem1,
        ImmutableSortedSet.of(Paths.get("foo")));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher2,
        modifiedFileHashCache,
        filesystem2,
        ImmutableSortedSet.of(Paths.get("foo")));

    assertThat(
        hasher1.hash(),
        not(equalTo(hasher2.hash())));
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
    PathHashing.hashPaths(
        hasher1,
        fileHashCache,
        filesystem1,
        ImmutableSortedSet.of(Paths.get("foo")));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPaths(
        hasher2,
        fileHashCache,
        filesystem2,
        ImmutableSortedSet.of(Paths.get("foo")));

    assertThat(
        hasher1.hash(),
        equalTo(hasher2.hash()));
  }
}
