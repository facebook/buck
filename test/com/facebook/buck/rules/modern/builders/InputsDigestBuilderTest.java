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
import com.facebook.buck.rules.modern.builders.thrift.Digest;
import com.facebook.buck.rules.modern.builders.thrift.Directory;
import com.facebook.buck.rules.modern.builders.thrift.DirectoryNode;
import com.facebook.buck.rules.modern.builders.thrift.FileNode;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Rule;
import org.junit.Test;

public class InputsDigestBuilderTest {
  private static final String DIR_PLACEHOLDER = "__dir__";
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAddFileInDirectoryBytes() throws IOException {
    InputsDigestBuilder digestBuilder =
        new InputsDigestBuilder(new DefaultDelegate(tmp.getRoot(), path -> null));
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
        new InputsDigestBuilder(new DefaultDelegate(tmp.getRoot(), path -> null));
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
        new InputsDigestBuilder(new DefaultDelegate(tmp.getRoot(), path -> null));
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
        new InputsDigestBuilder(new DefaultDelegate(tmp.getRoot(), path -> null));
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
    Directory directory = toDir(getBytes(inputs, inputs.getRootDigest()));
    addDebugData(inputs, "", directory, debugMap);
    return debugMap;
  }

  private void addDebugData(
      Inputs inputs, String dirName, Directory directory, Map<String, String> debugMap)
      throws IOException {
    debugMap.put(dirName, DIR_PLACEHOLDER);
    for (DirectoryNode dirNode : directory.directories) {
      addDebugData(
          inputs,
          String.format("%s/%s", dirName, dirNode.name),
          toDir(getBytes(inputs, dirNode.digest)),
          debugMap);
    }
    for (FileNode fileNode : directory.files) {
      debugMap.put(
          String.format("%s/%s%s", dirName, fileNode.name, fileNode.isExecutable ? "!" : ""),
          new String(getBytes(inputs, fileNode.digest), Charsets.UTF_8));
    }
  }

  private byte[] getBytes(Inputs inputs, Digest digest) throws IOException {
    return ByteStreams.toByteArray(inputs.getRequiredData().get(digest).get());
  }

  Directory toDir(byte[] data) throws ThriftException {
    Directory dir = new Directory();
    ThriftUtil.deserialize(ThriftProtocol.COMPACT, data, dir);
    return dir;
  }
}
