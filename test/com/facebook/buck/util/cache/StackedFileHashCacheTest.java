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

package com.facebook.buck.util.cache;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StackedFileHashCacheTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder tmp2 = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void usesFirstCache() throws IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    Path path = Paths.get("world.txt");
    filesystem.touch(path);

    Path fullPath = filesystem.resolve(path);
    DefaultFileHashCache innerCache = new DefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    cache.get(fullPath);
    assertTrue(innerCache.willGet(path));
  }

  @Test
  public void usesSecondCache() throws IOException {
    Path path = Paths.get("world.txt");
    Path fullPath = tmp2.getRootPath().resolve(path);

    DefaultFileHashCache innerCache =
        new DefaultFileHashCache(new ProjectFilesystem(tmp.getRootPath()));

    // The second project filesystem has the file.
    ProjectFilesystem filesystem2 = new ProjectFilesystem(tmp2.getRootPath());
    DefaultFileHashCache innerCache2 = new DefaultFileHashCache(filesystem2);
    filesystem2.touch(path);

    StackedFileHashCache cache =
        new StackedFileHashCache(
            ImmutableList.of(
                innerCache,
                innerCache2));
    cache.get(fullPath);
    assertTrue(innerCache2.willGet(path));
  }

  @Test
  public void skipsFirstCache() throws IOException {
    Path fullPath = Paths.get("some/path");
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    DefaultFileHashCache innerCache = new DefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(fullPath);
  }

  @Test
  public void skipsFirstCacheBecauseIgnored() throws IOException {
    Path path = Paths.get("world.txt");
    Path fullPath = tmp.getRootPath().resolve(path);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRootPath(), ImmutableSet.of(path));
    filesystem.touch(path);
    DefaultFileHashCache innerCache = new DefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(fullPath);
  }

}
