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

package com.facebook.buck.util.zip.collect;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;

public class ZipEntrySourceCollectionWriterTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void collectionWithDuplicatesWrittenToZip() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.APPEND);

    builder.addFile("tree.txt", workspace.resolve("tree.txt"));
    builder.addZipFile(workspace.resolve("zip-1.zip"));
    builder.addZipFile(workspace.resolve("zip-2.zip"));

    ZipEntrySourceCollection collection = builder.build();

    ZipEntrySourceCollectionWriter writer =
        new ZipEntrySourceCollectionWriter(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));

    Path output = tmp.newFolder("output").resolve("output.zip");
    writer.copyToZip(collection, output);

    ImmutableMap<String, Collection<String>> entries = readZipEntryContent(output).asMap();

    assertEquals(ImmutableMap.of("tree.txt", Arrays.asList("file", "zip-1", "zip-2")), entries);
  }

  @Test
  public void collectionWithoutDuplicatesWrittenToZip() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.OVERWRITE);

    builder.addFile("tree.txt", workspace.resolve("tree.txt"));
    builder.addZipFile(workspace.resolve("zip-1.zip"));
    builder.addZipFile(workspace.resolve("zip-2.zip"));

    ZipEntrySourceCollection collection = builder.build();

    ZipEntrySourceCollectionWriter writer =
        new ZipEntrySourceCollectionWriter(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));

    Path output = tmp.newFolder("output").resolve("output.zip");
    writer.copyToZip(collection, output);

    ImmutableMap<String, Collection<String>> entries = readZipEntryContent(output).asMap();

    assertEquals(ImmutableMap.of("tree.txt", Collections.singletonList("zip-2")), entries);
  }

  @Test
  public void directoryEntriesWrittenToZip() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.OVERWRITE);

    builder.addFile("dir/tree.txt", workspace.resolve("tree.txt"));

    ZipEntrySourceCollection collection = builder.build();

    ZipEntrySourceCollectionWriter writer =
        new ZipEntrySourceCollectionWriter(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()));

    Path output = tmp.newFolder("output").resolve("output.zip");
    writer.copyToZip(collection, output);

    ImmutableMap<String, Collection<String>> entries = readZipEntryContent(output).asMap();

    assertEquals(
        ImmutableMap.of(
            "dir/", Collections.singletonList(""),
            "dir/tree.txt", Collections.singletonList("file")),
        entries);
  }

  private ImmutableMultimap<String, String> readZipEntryContent(Path output) throws IOException {
    ImmutableMultimap.Builder<String, String> entryToContent = ImmutableMultimap.builder();

    try (ZipInputStream in =
        new ZipInputStream(new BufferedInputStream(Files.newInputStream(output)))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        entryToContent.put(
            e.getName(), new String(ByteStreams.toByteArray(in), StandardCharsets.UTF_8).trim());
      }
    }

    return entryToContent.build();
  }
}
