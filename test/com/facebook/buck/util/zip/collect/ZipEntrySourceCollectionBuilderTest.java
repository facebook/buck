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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ZipEntrySourceCollectionBuilderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void duplicateEntriesCauseFailure() {
    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.FAIL);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Duplicate entry \"entry1\" is coming from entry11 and entry12");

    builder.addFile("entry1", Paths.get("entry11"));
    builder.addFile("entry1", Paths.get("entry12"));
  }

  @Test
  public void excludedDuplicateEntriesDoNotCauseFailure() {
    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(
            ImmutableSet.of(Pattern.compile("entry1")), OnDuplicateEntry.FAIL);

    builder.addFile("entry1", Paths.get("entry11"));
    builder.addFile("entry1", Paths.get("entry12"));
  }

  @Test
  public void duplicateEntriesAreKeptInArchive() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.APPEND);

    builder.addFile("tree.txt", workspace.resolve("tree.txt"));
    builder.addZipFile(workspace.resolve("zip-1.zip"));
    builder.addZipFile(workspace.resolve("zip-2.zip"));

    ZipEntrySourceCollection collection = builder.build();

    assertEquals(3, collection.getSources().size());
    assertEquals(
        ImmutableList.of("tree.txt", "tree.txt", "tree.txt"),
        collection.getSources().stream()
            .map(ZipEntrySource::getEntryName)
            .collect(ImmutableList.toImmutableList()));
    assertEquals(
        ImmutableList.of("tree.txt", "zip-1.zip", "zip-2.zip"),
        collection.getSources().stream()
            .map(ZipEntrySource::getSourceFilePath)
            .map(p -> tmp.getRoot().relativize(p).toString())
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void duplicateEntriesAreNotKeptInArchiveWhenExcluded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(
            ImmutableSet.of(Pattern.compile("tree.txt")), OnDuplicateEntry.APPEND);

    builder.addFile("tree1.txt", workspace.resolve("tree.txt"));
    builder.addZipFile(workspace.resolve("zip-1.zip"));
    builder.addZipFile(workspace.resolve("zip-2.zip"));

    ZipEntrySourceCollection collection = builder.build();

    assertEquals(1, collection.getSources().size());
    assertEquals(
        ImmutableList.of("tree1.txt"),
        collection.getSources().stream()
            .map(ZipEntrySource::getEntryName)
            .collect(ImmutableList.toImmutableList()));
    assertEquals(
        ImmutableList.of("tree.txt"),
        collection.getSources().stream()
            .map(ZipEntrySource::getSourceFilePath)
            .map(p -> tmp.getRoot().relativize(p).toString())
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void duplicateEntriesAreOverwrittenInArchive() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "zip-collector", tmp);
    workspace.setUp();

    ZipEntrySourceCollectionBuilder builder =
        new ZipEntrySourceCollectionBuilder(ImmutableSet.of(), OnDuplicateEntry.OVERWRITE);

    builder.addFile("tree.txt", workspace.resolve("tree.txt"));
    builder.addZipFile(workspace.resolve("zip-1.zip"));
    builder.addZipFile(workspace.resolve("zip-2.zip"));

    ZipEntrySourceCollection collection = builder.build();

    assertEquals(1, collection.getSources().size());
    assertEquals(
        ImmutableList.of("tree.txt"),
        collection.getSources().stream()
            .map(ZipEntrySource::getEntryName)
            .collect(ImmutableList.toImmutableList()));
    assertEquals(
        ImmutableList.of("zip-2.zip"),
        collection.getSources().stream()
            .map(ZipEntrySource::getSourceFilePath)
            .map(p -> tmp.getRoot().relativize(p).toString())
            .collect(ImmutableList.toImmutableList()));
  }
}
