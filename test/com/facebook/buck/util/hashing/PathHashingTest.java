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

package com.facebook.buck.util.hashing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.FakeProjectFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link PathHashing}. */
public class PathHashingTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();
  @Rule public TemporaryPaths temporaryPaths1 = new TemporaryPaths();
  @Rule public TemporaryPaths temporaryPaths2 = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private ProjectFileHashCache fileHashCache;
  private ProjectFileHashCache modifiedFileHashCache;

  @Before
  public void setUp() throws IOException {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryPaths.getRoot());
    fileHashCache =
        FakeProjectFileHashCache.createFromStrings(
            projectFilesystem,
            ImmutableMap.of(
                "foo/foo.txt", "abcdef",
                "foo/bar.txt", "abcdef",
                "foo/baz.txt", "abcdef"));
    modifiedFileHashCache =
        FakeProjectFileHashCache.createFromStrings(
            projectFilesystem,
            ImmutableMap.of(
                "foo/foo.txt", "123456",
                "foo/bar.txt", "123456",
                "foo/baz.txt", "123456"));
  }

  @Test
  public void sameContentsSameNameHaveSameHash() throws IOException {
    Path path = Paths.get("foo/bar.txt");

    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths1.getRoot());
    filesystem1.createParentDirs(path);
    filesystem1.touch(path);
    filesystem1.setLastModifiedTime(path, FileTime.fromMillis(0));

    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths2.getRoot());
    filesystem2.createParentDirs(path);
    filesystem2.touch(path);
    filesystem2.setLastModifiedTime(path, FileTime.fromMillis(0));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), equalTo(hasher2.hash()));
  }

  @Test
  public void sameContentsDifferentNameHaveDifferentHashes() throws IOException {
    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths1.getRoot());
    Path path1 = Paths.get("foo/bar.txt");
    touchPath(filesystem1, path1);

    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths2.getRoot());
    Path path2 = Paths.get("foo/baz.txt");
    touchPath(filesystem2, path2);

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), not(equalTo(hasher2.hash())));
  }

  @Test
  public void sameNameDifferentContentsHaveDifferentHashes() throws IOException {
    Path path = Paths.get("foo/bar.txt");

    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths1.getRoot());
    touchPath(filesystem1, path);

    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths2.getRoot());
    touchPath(filesystem2, path);

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, modifiedFileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), not(equalTo(hasher2.hash())));
  }

  @Test
  public void hashDoesNotDependOnFilesystemIterationOrder() throws IOException {
    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths1.getRoot());
    touchPath(filesystem1, Paths.get("foo/foo.txt"));
    touchPath(filesystem1, Paths.get("foo/bar.txt"));
    touchPath(filesystem1, Paths.get("foo/baz.txt"));

    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(temporaryPaths1.getRoot());
    touchPath(filesystem2, Paths.get("foo/foo.txt"));
    touchPath(filesystem2, Paths.get("foo/bar.txt"));
    touchPath(filesystem2, Paths.get("foo/baz.txt"));

    Hasher hasher1 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher1, fileHashCache, filesystem1, Paths.get("foo"));

    Hasher hasher2 = Hashing.sha1().newHasher();
    PathHashing.hashPath(hasher2, fileHashCache, filesystem2, Paths.get("foo"));

    assertThat(hasher1.hash(), equalTo(hasher2.hash()));
  }

  private static void touchPath(ProjectFilesystem filesystem, Path path) throws IOException {
    filesystem.createParentDirs(path);
    filesystem.touch(path);
    filesystem.setLastModifiedTime(path, FileTime.fromMillis(0));
  }
}
