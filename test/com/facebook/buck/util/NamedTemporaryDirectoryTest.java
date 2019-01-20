/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.*;

import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class NamedTemporaryDirectoryTest {
  @Test
  public void testDirectoryIsCreated() throws IOException {
    try (NamedTemporaryDirectory temp = new NamedTemporaryDirectory("prefix")) {
      assertTrue(Files.exists(temp.getPath()));
      assertFalse(Files.isSymbolicLink(temp.getPath()));
      assertTrue(Files.isDirectory(temp.getPath()));
    }
  }

  @Test
  public void testDirectoryIsDeleted() throws IOException {
    Path path;
    try (NamedTemporaryDirectory temp = new NamedTemporaryDirectory("prefix")) {
      path = temp.getPath();
    }
    assertFalse(Files.exists(path));
  }

  @Test
  public void testFileContentsAreDeleted() throws IOException {
    Path root;
    Path path;
    try (NamedTemporaryDirectory temp = new NamedTemporaryDirectory("prefix")) {
      root = temp.getPath();
      path = root.resolve("some.file");
      Files.write(path, "data".getBytes(Charsets.UTF_8));
      Files.write(root.resolve("other.file"), "data2".getBytes(Charsets.UTF_8));
    }
    assertFalse(Files.exists(path));
    assertFalse(Files.exists(root));
  }

  @Test
  public void testSubdirectoriesAreDeleted() throws IOException {
    Path root;
    Path subdir;
    Path path;
    try (NamedTemporaryDirectory temp = new NamedTemporaryDirectory("prefix")) {
      root = temp.getPath();
      subdir = root.resolve("subdir");
      Files.createDirectories(subdir);
      path = subdir.resolve("some.file");
      Files.write(path, "data".getBytes(Charsets.UTF_8));
      Files.write(subdir.resolve("other"), "data".getBytes(Charsets.UTF_8));
      Files.write(root.resolve("other"), "data".getBytes(Charsets.UTF_8));
    }
    assertFalse(Files.exists(path));
    assertFalse(Files.exists(subdir));
    assertFalse(Files.exists(root));
  }
}
