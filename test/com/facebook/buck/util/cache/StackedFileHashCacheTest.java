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

import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.HashingDeterministicJarWriter;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarOutputStream;

public class StackedFileHashCacheTest {

  private static final String SOME_FILE_INSIDE_JAR = "SomeClass.class";
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
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    cache.get(fullPath);
    assertTrue(innerCache.willGet(path));
  }

  @Test
  public void usesFirstCacheForArchivePath() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Path path = Paths.get("world.jar");
    writeJarWithHashes(filesystem, path);

    Path fullPath = filesystem.resolve(path);
    ArchiveMemberPath archiveMemberPath = ArchiveMemberPath.of(
        fullPath,
        Paths.get(SOME_FILE_INSIDE_JAR));
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    cache.get(archiveMemberPath);
    assertTrue(innerCache.willGet(archiveMemberPath));
  }

  @Test
  public void usesSecondCache() throws IOException {
    Path path = Paths.get("world.txt");
    Path fullPath = tmp2.getRootPath().resolve(path);

    FileHashCache innerCache =
        DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(tmp.getRootPath()));

    // The second project filesystem has the file.
    ProjectFilesystem filesystem2 = new ProjectFilesystem(tmp2.getRootPath());
    FileHashCache innerCache2 = DefaultFileHashCache.createDefaultFileHashCache(filesystem2);
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
  public void usesSecondCacheForArchivePath() throws IOException {
    Path path = Paths.get("world.jar");
    Path fullPath = tmp2.getRootPath().resolve(path);

    FileHashCache innerCache =
        DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(tmp.getRootPath()));

    // The second project filesystem has the file.
    ProjectFilesystem filesystem2 = new ProjectFilesystem(tmp2.getRootPath());
    FileHashCache innerCache2 = DefaultFileHashCache.createDefaultFileHashCache(filesystem2);
    writeJarWithHashes(filesystem2, fullPath);

    ArchiveMemberPath archiveMemberPath = ArchiveMemberPath.of(
        fullPath,
        Paths.get(SOME_FILE_INSIDE_JAR));
    StackedFileHashCache cache =
        new StackedFileHashCache(
            ImmutableList.of(
                innerCache,
                innerCache2));
    cache.get(archiveMemberPath);
    assertTrue(innerCache2.willGet(archiveMemberPath));
  }

  @Test
  public void skipsFirstCache() throws IOException {
    Path fullPath = Paths.get("some/path");
    ProjectFilesystem filesystem = new FakeProjectFilesystem(tmp.getRoot());
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(filesystem.resolve(fullPath));
  }

  @Test
  public void skipsFirstCacheForArchiveMemberPath() throws IOException {
    Path fullPath = Paths.get("world.jar");
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRootPath());
    writeJarWithHashes(filesystem, filesystem.resolve(fullPath));
    ArchiveMemberPath archiveMemberPath = ArchiveMemberPath.of(
        filesystem.resolve(fullPath),
        Paths.get("Nonexistent.class"));
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(archiveMemberPath);
  }

  @Test
  public void skipsFirstCacheBecauseIgnored() throws IOException {
    Config config = ConfigBuilder.createFromText(
        "[project]",
        "ignore = world.txt");
    Path path = Paths.get("world.txt");
    Path fullPath = tmp.getRootPath().resolve(path);
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRootPath(), config);
    filesystem.touch(path);
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(filesystem.resolve(fullPath));
  }

  @Test
  public void skipsFirstCacheBecauseIgnoredForArchiveMemberPath() throws IOException {
    Config config = ConfigBuilder.createFromText(
        "[project]",
        "ignore = world.jar");
    Path fullPath = Paths.get("world.jar");
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRootPath(), config);
    writeJarWithHashes(filesystem, filesystem.resolve(fullPath));
    ArchiveMemberPath archiveMemberPath = ArchiveMemberPath.of(
        filesystem.resolve(fullPath),
        Paths.get("Nonexistent.class"));
    FileHashCache innerCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    StackedFileHashCache cache = new StackedFileHashCache(ImmutableList.of(innerCache));
    expectedException.expect(NoSuchFileException.class);
    cache.get(archiveMemberPath);
  }

  private void writeJarWithHashes(ProjectFilesystem filesystem, Path path) throws IOException {
    try (HashingDeterministicJarWriter jar = new HashingDeterministicJarWriter(
        new JarOutputStream(filesystem.newFileOutputStream(path)))) {
      jar
          .writeEntry(
              SOME_FILE_INSIDE_JAR,
              ByteSource.wrap("fake contents".getBytes(StandardCharsets.UTF_8)));
    }
  }
}
