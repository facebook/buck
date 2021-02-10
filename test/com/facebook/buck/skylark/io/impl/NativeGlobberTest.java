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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Files;
import java.util.Collections;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class NativeGlobberTest {
  private AbsPath root;
  private Globber globber;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    root = projectFilesystem.getRootPath();
    globber = NativeGlobber.create(root);
  }

  @Test
  public void testGlobFindsRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("child");
    Files.createDirectory(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    assertThat(
        globber.run(Collections.singleton("**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsShallowRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("child");
    Files.createDirectory(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    assertThat(
        globber.run(Collections.singleton("child/**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsDeepRecursiveIncludes() throws Exception {
    AbsPath child = root.resolve("dir").resolve("child");
    Files.createDirectories(child.getPath());
    Files.write(child.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(child.resolve("bar.jpg").getPath(), new byte[0]);
    assertThat(
        globber.run(Collections.singleton("dir/**/child/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("dir/child/bar.txt", "dir/child/foo.txt")));
  }

  @Test
  public void testGlobFindsIncludes() throws Exception {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("bar.txt", "foo.txt")));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws Exception {
    Files.write(root.resolve("foo.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.txt").getPath(), new byte[0]);
    Files.write(root.resolve("bar.jpg").getPath(), new byte[0]);
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.singleton("bar.txt"), false),
        equalTo(ImmutableSet.of("foo.txt")));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("some_dir")));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    Files.createDirectories(root.resolve("some_dir").getPath());
    AbsPath buildFile = root.resolve("BUCK");
    Files.write(
        buildFile.getPath(),
        ImmutableList.of("txts = glob(['some_dir'], exclude_directories=True)"));
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), true),
        equalTo(ImmutableSet.of()));
  }

  @Test
  public void doesNotReturnNonexistentDirectory() throws Exception {
    assertThat(
        globber.run(Collections.singleton("does/not/exist.txt"), Collections.emptySet(), false),
        Matchers.empty());
  }

  @Test
  public void doesNotReturnNonexistentFile() throws Exception {
    assertThat(
        globber.run(Collections.singleton("does_not_exist.txt"), Collections.emptySet(), false),
        Matchers.empty());
  }
}
