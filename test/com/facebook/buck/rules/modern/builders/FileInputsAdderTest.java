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

package com.facebook.buck.rules.modern.builders;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.remoteexecution.util.FileTreeBuilder;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.TreeBuilder;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class FileInputsAdderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private final Map<Path, HashCode> fileHashes = new HashMap<>();
  private final FileTreeBuilder builder = new FileTreeBuilder();
  private FileInputsAdder adder;

  private static class Directory {
    public final SortedMap<String, Directory> children = new TreeMap<>();
    public final SortedMap<String, Path> symlinks = new TreeMap<>();
    public final SortedMap<String, String> files = new TreeMap<>();

    Directory addChild(String name, Directory dir) {
      children.put(name, dir);
      return this;
    }

    Directory addSymlink(String name, String link) {
      symlinks.put(name, Paths.get(link));
      return this;
    }

    Directory addFile(String name, int hash) {
      files.put(name, HashCode.fromInt(hash).toString());
      return this;
    }

    public void assertSame(Directory other, Path root) {
      assertEquals("In directories of " + root, children.keySet(), other.children.keySet());
      MoreIterables.forEachPair(
          children.entrySet(),
          other.children.entrySet(),
          (expected, actual) -> {
            assertEquals("In directories of " + root, expected.getKey(), actual.getKey());
            expected.getValue().assertSame(actual.getValue(), root.resolve(expected.getKey()));
          });
      MoreAsserts.assertIterablesEquals(
          "In symlinks of " + root, symlinks.entrySet(), other.symlinks.entrySet());
      MoreAsserts.assertIterablesEquals(
          "In files of " + root, files.entrySet(), other.files.entrySet());
    }

    public void assertSame(Directory other) {
      assertSame(other, Paths.get("/"));
    }
  }

  @Before
  public void setUp() {
    adder =
        new FileInputsAdder(
            builder,
            tmp.getRoot(),
            fileHashes::get,
            dir -> {
              if (!Files.isDirectory(dir)) {
                return null;
              }
              try (Stream<Path> listing = Files.list(dir)) {
                return listing.collect(Collectors.toList());
              }
            },
            link -> Files.isSymbolicLink(link) ? Files.readSymbolicLink(link) : null);
  }

  @Test
  public void testAddFile() throws IOException {
    Path file = tmp.newFile("file");
    fileHashes.put(file, HashCode.fromInt(1));
    adder.addInput(file);

    Directory result = getTree();

    new Directory().addFile("file", 1).assertSame(result);
  }

  @Test
  public void testAddFileInSubdir() throws IOException {
    tmp.newFolder("subdir1");
    Path file = tmp.newFile("subdir1/file");
    fileHashes.put(file, HashCode.fromInt(1));
    adder.addInput(file);

    Directory result = getTree();
    new Directory().addChild("subdir1", new Directory().addFile("file", 1)).assertSame(result);
  }

  @Test
  public void testAddDirectory() throws IOException {
    Path subdir = tmp.newFolder("subdir1");
    Path file1 = tmp.newFile("subdir1/file1");
    Path file2 = tmp.newFile("subdir1/file2");

    fileHashes.put(file1, HashCode.fromInt(1));
    fileHashes.put(file2, HashCode.fromInt(2));
    adder.addInput(subdir);

    Directory result = getTree();

    new Directory()
        .addChild("subdir1", new Directory().addFile("file1", 1).addFile("file2", 2))
        .assertSame(result);
  }

  @Test
  public void testAddSymlink() throws IOException {
    Path subdir = tmp.newFolder("subdir1");
    Path file1 = tmp.newFile("file1");
    Path link1 = subdir.resolve("link1");
    CreateSymlinksForTests.createSymLink(link1, file1);

    fileHashes.put(file1, HashCode.fromInt(1));

    adder.addInput(link1);

    Directory result = getTree();
    new Directory()
        .addChild("subdir1", new Directory().addSymlink("link1", "../file1"))
        .addFile("file1", 1)
        .assertSame(result);
  }

  @Test
  public void testAddExternalLink() throws IOException {
    Path link1 = tmp.getRoot().resolve("link1");
    Path absoluteTarget = tmp.getRoot().getParent().resolve("other_random_place");
    CreateSymlinksForTests.createSymLink(link1, absoluteTarget);

    adder.addInput(link1);

    Directory result = getTree();

    new Directory().addSymlink("link1", absoluteTarget.toString()).assertSame(result);
  }

  @Test
  public void testAddFileWithSymlinkParent() throws IOException {
    Path subdir = tmp.newFolder("subdir1");
    Path file1 = tmp.newFile("subdir1/file1");
    Path link1 = tmp.getRoot().resolve("link1");

    CreateSymlinksForTests.createSymLink(link1, subdir);
    fileHashes.put(file1, HashCode.fromInt(1));

    adder.addInput(link1.resolve("file1"));

    Directory result = getTree();

    new Directory()
        .addChild("subdir1", new Directory().addFile("file1", 1))
        .addSymlink("link1", "subdir1")
        .assertSame(result);
  }

  @Test
  public void testAddSymlinkChain() throws IOException {
    Path file1 = tmp.newFile("file1");

    Path link1 = tmp.getRoot().resolve("link1");
    Path link2 = tmp.getRoot().resolve("link2");
    Path link3 = tmp.getRoot().resolve("link3");

    Files.createSymbolicLink(link1, link2);
    Files.createSymbolicLink(link2, link3);
    Files.createSymbolicLink(link3, file1);

    fileHashes.put(file1, HashCode.fromInt(1));

    adder.addInput(link1);

    Directory result = getTree();

    new Directory()
        .addSymlink("link1", "link2")
        .addSymlink("link2", "link3")
        .addSymlink("link3", "file1")
        .addFile("file1", 1)
        .assertSame(result);
  }

  private Directory getTree() {
    return builder.buildTree(getTreeBuilder(new Directory()));
  }

  private TreeBuilder<Directory> getTreeBuilder(Directory self) {
    return new TreeBuilder<Directory>() {
      private void assertFree(String name) {
        Preconditions.checkState(!self.files.containsKey(name));
        Preconditions.checkState(!self.symlinks.containsKey(name));
        Preconditions.checkState(!self.children.containsKey(name));
      }

      @Override
      public TreeBuilder<Directory> addDirectory(String name) {
        assertFree(name);
        Directory child = new Directory();
        self.children.put(name, child);
        return getTreeBuilder(child);
      }

      @Override
      public void addFile(
          String name,
          String hash,
          int size,
          boolean isExecutable,
          ThrowingSupplier<InputStream, IOException> dataSupplier) {
        assertFree(name);
        self.files.put(name, hash);
      }

      @Override
      public void addSymlink(String name, Path path) {
        assertFree(name);
        self.symlinks.put(name, path);
      }

      @Override
      public Directory build() {
        return self;
      }
    };
  }
}
