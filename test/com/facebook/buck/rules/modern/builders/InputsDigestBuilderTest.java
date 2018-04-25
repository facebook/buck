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

import com.facebook.buck.rules.modern.builders.InputsDigestBuilder.DefaultDelegate;
import com.facebook.buck.rules.modern.builders.Protocol.Digest;
import com.facebook.buck.rules.modern.builders.Protocol.Directory;
import com.facebook.buck.rules.modern.builders.Protocol.DirectoryNode;
import com.facebook.buck.rules.modern.builders.Protocol.FileNode;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Rule;
import org.junit.Test;

public class InputsDigestBuilderTest {
  private static final Protocol PROTOCOL = new ThriftProtocol();
  private static final String DIR_PLACEHOLDER = "__dir__";
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAddFileInDirectoryBytes() throws IOException {
    InputsDigestBuilder digestBuilder =
        new InputsDigestBuilder(
            new DefaultDelegate(tmp.getRoot(), path -> null, PROTOCOL), PROTOCOL);
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> "hello world!".getBytes(Charsets.UTF_8), false);
    Inputs inputs = digestBuilder.build();
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files("/some/sub/dir/some.path", "hello world!")),
        toDebugMap(inputs));
  }

  @Test
  public void testAddExecutableFileInDirectoryBytes() throws IOException {
    InputsDigestBuilder digestBuilder =
        new InputsDigestBuilder(
            new DefaultDelegate(tmp.getRoot(), path -> null, PROTOCOL), PROTOCOL);
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> "hello world!".getBytes(Charsets.UTF_8), true);
    Inputs inputs = digestBuilder.build();
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files("/some/sub/dir/some.path!", "hello world!")),
        toDebugMap(inputs));
  }

  @Test
  public void testMultipleFiles() throws IOException {
    InputsDigestBuilder digestBuilder =
        new InputsDigestBuilder(
            new DefaultDelegate(tmp.getRoot(), path -> null, PROTOCOL), PROTOCOL);
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> "hello world!".getBytes(Charsets.UTF_8), false);
    digestBuilder.addFile(
        Paths.get("some/sub/dir/other.path"),
        () -> "goodbye world!".getBytes(Charsets.UTF_8),
        true);
    Inputs inputs = digestBuilder.build();
    assertEquals(
        makeExpected(
            directories("", "/some", "/some/sub", "/some/sub/dir"),
            files(
                "/some/sub/dir/some.path",
                "hello world!",
                "/some/sub/dir/other.path!",
                "goodbye world!")),
        toDebugMap(inputs));
  }

  @Test
  public void testMultipleDirectories() throws IOException {
    InputsDigestBuilder digestBuilder =
        new InputsDigestBuilder(
            new DefaultDelegate(tmp.getRoot(), path -> null, PROTOCOL), PROTOCOL);
    digestBuilder.addFile(
        Paths.get("some/sub/dir/some.path"), () -> "hello world!".getBytes(Charsets.UTF_8), false);
    digestBuilder.addFile(
        Paths.get("some/other/sub/dir/other.path"),
        () -> "goodbye world!".getBytes(Charsets.UTF_8),
        true);
    Inputs inputs = digestBuilder.build();
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
        toDebugMap(inputs));
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

  SortedMap<String, String> toDebugMap(Inputs inputs) throws IOException {
    SortedMap<String, String> debugMap = new TreeMap<>();
    Directory directory =
        PROTOCOL.parseDirectory(ByteBuffer.wrap(getBytes(inputs, inputs.getRootDigest())));
    addDebugData(inputs, "", directory, debugMap);
    return debugMap;
  }

  private void addDebugData(
      Inputs inputs, String dirName, Directory directory, Map<String, String> debugMap)
      throws IOException {
    debugMap.put(dirName, DIR_PLACEHOLDER);
    for (DirectoryNode dirNode : directory.getDirectoriesList()) {
      addDebugData(
          inputs,
          String.format("%s/%s", dirName, dirNode.getName()),
          PROTOCOL.parseDirectory(ByteBuffer.wrap(getBytes(inputs, dirNode.getDigest()))),
          debugMap);
    }
    for (FileNode fileNode : directory.getFilesList()) {
      debugMap.put(
          String.format(
              "%s/%s%s", dirName, fileNode.getName(), fileNode.getIsExecutable() ? "!" : ""),
          new String(getBytes(inputs, fileNode.getDigest()), Charsets.UTF_8));
    }
  }

  private byte[] getBytes(Inputs inputs, Digest digest) throws IOException {
    Preconditions.checkNotNull(inputs);
    Preconditions.checkNotNull(inputs.getRequiredData());
    Preconditions.checkNotNull(inputs.getRequiredData().get(digest));
    return ByteStreams.toByteArray(inputs.getRequiredData().get(digest).get());
  }
}
