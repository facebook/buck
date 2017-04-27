/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.zip.CustomJarOutputStream;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class DefaultFileHashCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void whenPathIsPutCacheContainsPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    assertTrue("Cache should contain path", cache.willGet(path));
  }

  @Test
  public void whenPathIsPutPathGetReturnsHash() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    assertEquals("Cache should contain hash", value.getHashCode(), cache.get(path));
  }

  @Test
  public void whenPathIsPutThenInvalidatedCacheDoesNotContainPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    Path path = new File("SomeClass.java").toPath();
    HashCodeAndFileType value = HashCodeAndFileType.ofFile(HashCode.fromInt(42));
    cache.loadingCache.put(path, value);
    assertTrue("Cache should contain path", cache.willGet(path));
    cache.invalidate(path);
    assertFalse("Cache should not contain pain", cache.willGet(path));
  }

  @Test
  public void invalidatingNonExistentEntryDoesNotThrow() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    Path path = new File("SomeClass.java").toPath();
    assertFalse("Cache should not contain pain", cache.willGet(path));
    cache.invalidate(path);
    assertFalse("Cache should not contain pain", cache.willGet(path));
  }

  @Test
  public void getMissingPathThrows() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    expectedException.expect(IOException.class);
    cache.get(filesystem.getPath("hello.java"));
  }

  @Test
  public void whenPathsArePutThenInvalidateAllRemovesThem() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path path1 = Paths.get("path1");
    filesystem.writeContentsToPath("contents1", path1);
    cache.get(path1);
    assertTrue(cache.willGet(path1));

    Path path2 = Paths.get("path2");
    filesystem.writeContentsToPath("contents2", path2);
    cache.get(path2);
    assertTrue(cache.willGet(path2));

    // Verify that `invalidateAll` clears everything from the cache.
    assertFalse(cache.loadingCache.asMap().isEmpty());
    cache.invalidateAll();

    assertTrue(cache.loadingCache.asMap().isEmpty());
  }

  @Test
  public void whenDirectoryIsPutThenInvalidatedCacheDoesNotContainPathOrChildren()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path dir = filesystem.getPath("dir");
    filesystem.mkdirs(dir);
    Path child1 = dir.resolve("child1");
    filesystem.touch(child1);
    Path child2 = dir.resolve("child2");
    filesystem.touch(child2);

    cache.get(dir);
    assertTrue(cache.willGet(dir));
    assertTrue(cache.willGet(child1));
    assertTrue(cache.willGet(child2));

    cache.invalidate(dir);
    assertNull(cache.loadingCache.getIfPresent(dir));
    assertNull(cache.loadingCache.getIfPresent(child1));
    assertNull(cache.loadingCache.getIfPresent(child2));
  }

  @Test
  public void whenJarMemberWithHashInManifestIsQueriedThenCacheCorrectlyObtainsIt()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path abiJarPath = Paths.get("test-abi.jar");
    Path memberPath = Paths.get("SomeClass.class");
    String memberContents = "Some contents";
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.setEntryHashingEnabled(true);
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
    }

    HashCode actual = cache.get(ArchiveMemberPath.of(abiJarPath, memberPath));
    HashCode expected = Hashing.murmur3_128().hashString(memberContents, StandardCharsets.UTF_8);

    assertEquals(expected, actual);
  }

  @Test(expected = NoSuchFileException.class)
  public void whenJarMemberWithoutHashInManifestIsQueriedThenThrow() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path abiJarPath = Paths.get("test-abi.jar");
    Path memberPath = Paths.get("Unhashed.txt");
    String memberContents = "Some contents";
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.setEntryHashingEnabled(true);
      jar.writeEntry(
          "SomeClass.class",
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
      jar.setEntryHashingEnabled(false);
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream(memberContents.getBytes(StandardCharsets.UTF_8)));
    }

    cache.get(ArchiveMemberPath.of(abiJarPath, memberPath));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void whenJarMemberWithoutManifestIsQueriedThenThrow() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path abiJarPath = Paths.get("no-manifest.jar");
    Path memberPath = Paths.get("Empty.class");

    try (JarOutputStream jar = new JarOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.putNextEntry(new JarEntry(memberPath.toString()));
      jar.write("Contents".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }

    cache.get(ArchiveMemberPath.of(abiJarPath, memberPath));
  }

  @Test(expected = NoSuchFileException.class)
  public void whenJarMemberWithEmptyManifestIsQueriedThenThrow() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());

    Path abiJarPath = Paths.get("empty-manifest.jar");
    Path memberPath = Paths.get("Empty.class");

    try (CustomZipOutputStream jar =
        ZipOutputStreams.newOutputStream(filesystem.newFileOutputStream(abiJarPath))) {
      jar.writeEntry(JarFile.MANIFEST_NAME, new ByteArrayInputStream(new byte[0]));
      jar.writeEntry(
          memberPath.toString(),
          new ByteArrayInputStream("Contents".getBytes(StandardCharsets.UTF_8)));
    }

    cache.get(ArchiveMemberPath.of(abiJarPath, memberPath));
  }

  @Test
  public void getSizeOfMissingPathThrows() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path input = filesystem.getPath("input");
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    expectedException.expect(RuntimeException.class);
    cache.getSize(input);
  }

  @Test
  public void getSizeOfFile() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[123], input);
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    assertThat(cache.getSize(input), Matchers.equalTo(123L));
  }

  @Test
  public void getSizeOfDirectory() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path input = filesystem.getPath("input");
    filesystem.mkdirs(input);
    filesystem.writeBytesToPath(new byte[123], input.resolve("file1"));
    filesystem.writeBytesToPath(new byte[123], input.resolve("file2"));
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    assertThat(cache.getSize(input), Matchers.equalTo(246L));
  }

  @Test
  public void getFileSizeInvalidation() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path input = filesystem.getPath("input");
    filesystem.writeBytesToPath(new byte[123], input);
    DefaultFileHashCache cache = new DefaultFileHashCache(filesystem, Optional.empty());
    cache.getSize(input);
    cache.invalidate(input);
    assertNull(cache.sizeCache.getIfPresent(input));
  }

  @Test
  public void thatBuckoutCacheWillGetIsCorrect() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path buckOut = Paths.get("buck-out");
    filesystem.mkdirs(buckOut);
    Path buckOutFile = buckOut.resolve("file.txt");
    Path otherFile = Paths.get("file.txt");
    filesystem.writeContentsToPath("data", buckOutFile);
    filesystem.writeContentsToPath("other data", otherFile);
    DefaultFileHashCache cache =
        new DefaultFileHashCache(filesystem, Optional.of(Paths.get("buck-out")));
    assertTrue(cache.willGet(filesystem.getPath("buck-out/file.txt")));
    assertFalse(cache.willGet(filesystem.getPath("file.txt")));
  }
}
