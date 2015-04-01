/*
 * Copyright 2014-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxCompilationDatabase.JsonSerializableDatabaseEntry;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CxxCompilationDatabaseIntegrationTest {

  private static final String COMPILER_PATH = "/usr/bin/g++";

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void binaryWithDependenciesCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//:binary_with_dep#compilation-database");

    assertEquals(
        Paths.get(
            "buck-out/gen/__binary_with_dep#compilation-database.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    String binaryHeaderSymlinkTreeFolder =
        "buck-out/gen/binary_with_dep#compilation-database,default,header-symlink-tree";
    String binaryExportedHeaderSymlinkTreeFoler =
        "buck-out/gen/library_with_header#default,exported-header-symlink-tree";

    assertTrue(Files.exists(tmp.getRootPath().resolve(binaryHeaderSymlinkTreeFolder)));
    assertTrue(Files.exists(tmp.getRootPath().resolve(binaryExportedHeaderSymlinkTreeFoler)));

    String libraryHeaderSymlinkTreeFolder =
        "buck-out/gen/library_with_header#default,header-symlink-tree";
    String librayExportedHeaderSymlinkTreeFoler =
        "buck-out/gen/library_with_header#default,exported-header-symlink-tree";

    // Verify that symlink folders for headers are created and header file is linked.
    assertTrue(Files.exists(tmp.getRootPath().resolve(libraryHeaderSymlinkTreeFolder)));
    assertTrue(Files.exists(tmp.getRootPath().resolve(librayExportedHeaderSymlinkTreeFoler)));
    assertTrue(
        Files.exists(tmp.getRootPath().resolve(librayExportedHeaderSymlinkTreeFoler + "/bar.h")));


    Gson gson = new Gson();
    FileReader fileReader = new FileReader(compilationDatabase);
    List<JsonSerializableDatabaseEntry> entries = gson
        .fromJson(
            fileReader, new TypeToken<List<JsonSerializableDatabaseEntry>>() {
            }.getType());
    Map<String, JsonSerializableDatabaseEntry> fileToEntry = Maps.newHashMap();
    for (JsonSerializableDatabaseEntry entry : entries) {
      fileToEntry.put(entry.file, entry);
    }

    assertEquals(2, entries.size());

    assertHasEntry(
        fileToEntry,
        "foo.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-c")
            .add("-x")
            .add("c++")
            .add("-I")
            .add(binaryHeaderSymlinkTreeFolder)
            .add("-I")
            .add(binaryExportedHeaderSymlinkTreeFoler)
            .add("-o")
            .add("buck-out/bin/binary_with_dep#compilation-database,compile-foo.cpp.o,default" +
                    "/foo.cpp.o")
            .add("foo.cpp")
            .build());

    assertHasEntry(
        fileToEntry,
        "bar.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-c")
            .add("-x")
            .add("c++")
            .add("-I")
            .add(libraryHeaderSymlinkTreeFolder)
            .add("-I")
            .add(librayExportedHeaderSymlinkTreeFoler)
            .add("-o")
            .add("buck-out/bin/library_with_header#compile-bar.cpp.o,default/bar.cpp.o")
            .add("bar.cpp")
            .build());
  }

  @Test
  public void libraryCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    File compilationDatabase = workspace.buildAndReturnOutput(
        "//:library_with_header#default,compilation-database");
    assertEquals(
        Paths.get(
            "buck-out/gen/__library_with_header#compilation-database,default.json"),
        tmp.getRootPath().relativize(compilationDatabase.toPath()));

    String headerSymlinkTreeFolder = "buck-out/gen/library_with_header#default,header-symlink-tree";
    String exportedHeaderSymlinkTreeFoler =
        "buck-out/gen/library_with_header#default,exported-header-symlink-tree";

    // Verify that symlink folders for headers are created.
    assertTrue(Files.exists(tmp.getRootPath().resolve(headerSymlinkTreeFolder)));
    assertTrue(Files.exists(tmp.getRootPath().resolve(exportedHeaderSymlinkTreeFoler)));

    Gson gson = new Gson();
    FileReader fileReader = new FileReader(compilationDatabase);
    List<JsonSerializableDatabaseEntry> entries = gson
        .fromJson(
            fileReader, new TypeToken<List<JsonSerializableDatabaseEntry>>() {
        }.getType());
    Map<String, JsonSerializableDatabaseEntry> fileToEntry = Maps.newHashMap();
    for (JsonSerializableDatabaseEntry entry : entries) {
      fileToEntry.put(entry.file, entry);
    }

    assertEquals(1, entries.size());

    assertHasEntry(
        fileToEntry,
        "bar.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-c")
            .add("-x")
            .add("c++")
            .add("-fPIC")
            .add("-I")
            .add(headerSymlinkTreeFolder)
            .add("-I")
            .add(exportedHeaderSymlinkTreeFoler)
            .add("-fPIC")
            .add("-o")
            .add("buck-out/bin/library_with_header#compile-pic-bar.cpp.o,default/bar.cpp.o")
            .add("bar.cpp")
            .build());

  }

  private void assertHasEntry(
      Map<String, JsonSerializableDatabaseEntry> fileToEntry,
      String fileName,
      List<String> command) throws IOException {
    String key = tmp.getRootPath().resolve(fileName).toRealPath().toString();
    JsonSerializableDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);
    assertEquals(
        Joiner.on(' ').join(
            Iterables.transform(
                command,
                Escaper.SHELL_ESCAPER)),
        entry.command);
  }
}
