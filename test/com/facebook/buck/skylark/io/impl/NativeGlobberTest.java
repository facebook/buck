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

package com.facebook.buck.skylark.io.impl;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.util.Collections;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class NativeGlobberTest {
  private Path root;
  private Globber globber;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(projectFilesystem.getRootPath().toString());
    globber = NativeGlobber.create(root);
  }

  @Test
  public void testGlobFindsRecursiveIncludes() throws Exception {
    Path child = root.getChild("child");
    child.createDirectory();
    FileSystemUtils.createEmptyFile(child.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsShallowRecursiveIncludes() throws Exception {
    Path child = root.getChild("child");
    child.createDirectory();
    FileSystemUtils.createEmptyFile(child.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("child/**/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("child/bar.txt", "child/foo.txt")));
  }

  @Test
  public void testGlobFindsDeepRecursiveIncludes() throws Exception {
    Path child = root.getChild("dir").getChild("child");
    child.createDirectoryAndParents();
    FileSystemUtils.createEmptyFile(child.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(child.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("dir/**/child/*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("dir/child/bar.txt", "dir/child/foo.txt")));
  }

  @Test
  public void testGlobFindsIncludes() throws Exception {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("bar.txt", "foo.txt")));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws Exception {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.singleton("bar.txt"), false),
        equalTo(ImmutableSet.of("foo.txt")));
  }

  @Test
  public void testMatchingDirectoryIsReturnedWhenDirsAreNotExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    assertThat(
        globber.run(Collections.singleton("some_dir"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("some_dir")));
  }

  @Test
  public void testMatchingDirectoryIsNotReturnedWhenDirsAreExcluded() throws Exception {
    FileSystemUtils.createDirectoryAndParents(root.getChild("some_dir"));
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(
        buildFile, "txts = glob(['some_dir'], exclude_directories=True)");
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
