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
import static org.junit.Assert.*;

import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class SimpleGlobberTest {
  private Path root;
  private Globber globber;

  @Before
  public void setUp() {
    InMemoryFileSystem fileSystem = new InMemoryFileSystem();
    root = fileSystem.getRootDirectory();
    globber = SimpleGlobber.create(root);
  }

  @Test
  public void testGlobFindsIncludes() throws IOException, InterruptedException {
    FileSystemUtils.createEmptyFile(root.getChild("foo.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.txt"));
    FileSystemUtils.createEmptyFile(root.getChild("bar.jpg"));
    assertThat(
        globber.run(Collections.singleton("*.txt"), Collections.emptySet(), false),
        equalTo(ImmutableSet.of("bar.txt", "foo.txt")));
  }

  @Test
  public void testGlobExcludedElementsAreNotReturned() throws IOException, InterruptedException {
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
}
