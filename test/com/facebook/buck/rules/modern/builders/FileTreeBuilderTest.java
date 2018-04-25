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

import static org.junit.Assert.*;

import com.facebook.buck.rules.modern.builders.FileTreeBuilder.InputFile;
import com.facebook.buck.rules.modern.builders.FileTreeBuilder.TreeBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Rule;
import org.junit.Test;

public class FileTreeBuilderTest {
  private static final String DIR_PLACEHOLDER = "__dir__";
  private final HashFunction hasher = Hashing.sipHash24();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAddFileInDirectoryBytes() throws IOException {
    FileTreeBuilder digestBuilder = new FileTreeBuilder();
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> newFileNode("hello world!", false));
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files("/some/sub/dir/some.path", "hello world!")),
        toDebugMap(digestBuilder));
  }

  @Test
  public void testAddExecutableFileInDirectoryBytes() throws IOException {
    FileTreeBuilder digestBuilder = new FileTreeBuilder();
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> newFileNode("hello world!", true));
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files("/some/sub/dir/some.path!", "hello world!")),
        toDebugMap(digestBuilder));
  }

  @Test
  public void testMultipleFiles() throws IOException {
    FileTreeBuilder digestBuilder = new FileTreeBuilder();
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> newFileNode("hello world!", false));
    digestBuilder.addFile(
        Paths.get("some/sub/dir/other.path"), () -> newFileNode("goodbye world!", true));
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files(
                "/some/sub/dir/some.path",
                "hello world!",
                "/some/sub/dir/other.path!",
                "goodbye world!")),
        toDebugMap(digestBuilder));
  }

  @Test
  public void testMultipleDirectories() throws IOException {
    FileTreeBuilder digestBuilder = new FileTreeBuilder();
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> newFileNode("hello world!", false));
    digestBuilder.addFile(
        Paths.get("some/other/sub/dir/other.path"), () -> newFileNode("goodbye world!", true));
    assertEquals(
        makeExpected(
            directories(
                "",
                "/some",
                "/some/other",
                "/some/other/sub",
                "/some/other/sub/dir",
                "/some/sub",
                "/some/sub/dir"),
            files(
                "/some/sub/dir/some.path",
                "hello world!",
                "/some/other/sub/dir/other.path!",
                "goodbye world!")),
        toDebugMap(digestBuilder));
  }

  private InputFile newFileNode(String content, boolean isExecutable) {
    byte[] bytes = content.getBytes(Charsets.UTF_8);
    return new InputFile(
        hasher.hashBytes(bytes).toString(),
        bytes.length,
        isExecutable,
        () -> new ByteArrayInputStream(bytes));
  }

  private SortedMap<String, String> makeExpected(List<String> dirs, Map<String, String> files) {
    return ImmutableSortedMap.<String, String>naturalOrder()
        .putAll(dirs.stream().collect(ImmutableMap.toImmutableMap(k -> k, k -> DIR_PLACEHOLDER)))
        .putAll(files)
        .build();
  }

  private List<String> directories(String... args) {
    return Arrays.asList(args);
  }

  private Map<String, String> files(String... args) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (int i = 0; i < args.length; i += 2) {
      builder.put(args[i], args[i + 1]);
    }
    return builder.build();
  }

  SortedMap<String, String> toDebugMap(FileTreeBuilder builder) {
    SortedMap<String, String> debugMap = new TreeMap<>();
    builder.buildTree(debugMapBuilder(debugMap, ""));
    return debugMap;
  }

  private TreeBuilder<Void> debugMapBuilder(SortedMap<String, String> debugMap, String root) {
    debugMap.put(root, "__dir__");
    return new TreeBuilder<Void>() {
      @Override
      public TreeBuilder<Void> addDirectory(String name) {
        return debugMapBuilder(debugMap, String.format("%s/%s", root, name));
      }

      @Override
      public void addFile(
          String name,
          String hash,
          int size,
          boolean isExecutable,
          ThrowingSupplier<InputStream, IOException> dataSupplier) {
        try (InputStream dataStream = dataSupplier.asSupplier().get()) {
          debugMap.put(
              String.format("%s/%s%s", root, name, isExecutable ? "!" : ""),
              new String(ByteStreams.toByteArray(dataStream), Charsets.UTF_8));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public Void build() {
        return null;
      }
    };
  }
}
