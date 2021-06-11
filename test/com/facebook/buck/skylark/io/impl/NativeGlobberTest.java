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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** @see GlobberTest which runs the same tests for native and watchman globbers. */
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
  public void crashesIfEncountersMatchingBrokenSymlink() throws Exception {
    Files.createDirectories(root.resolve("foo").getPath());
    Files.write(root.resolve("foo/bar.h").getPath(), new byte[0]);
    Files.createSymbolicLink(
        root.resolve("foo/non-existent.cpp").getPath(),
        root.resolve("foo/non-existent-target.cpp").getPath());
    thrown.expect(NoSuchFileException.class);
    globber.run(ImmutableList.of("foo/*.cpp"), ImmutableList.of(), false);
  }
}
