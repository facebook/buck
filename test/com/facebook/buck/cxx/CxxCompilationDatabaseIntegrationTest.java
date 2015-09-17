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

import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CxxCompilationDatabaseIntegrationTest {

  private static final String COMPILER_PATH =
      Platform.detect() == Platform.MACOS ? "/usr/bin/clang++" : "/usr/bin/g++";
  private static final ImmutableList<String> COMPILER_SPECIFIC_FLAGS =
      Platform.detect() == Platform.MACOS ?
          ImmutableList.of(
              "-Xclang",
              "-fdebug-compilation-dir",
              "-Xclang",
              "." + Strings.repeat("/", 249)) :
          ImmutableList.<String>of();
  private static final boolean PREPROCESSOR_SUPPORTS_HEADER_MAPS =
      Platform.detect() == Platform.MACOS;
  private static final ImmutableList<String> EXTRA_FLAGS_FOR_HEADER_MAPS =
      PREPROCESSOR_SUPPORTS_HEADER_MAPS ?
          ImmutableList.of("-I", BuckConstant.BUCK_OUTPUT_DIRECTORY) :
          ImmutableList.<String>of();

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void binaryWithDependenciesCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    Path compilationDatabase = workspace.buildAndReturnOutput(
        "//:binary_with_dep#compilation-database");

    assertEquals(
        Paths.get(
            "buck-out/gen/__binary_with_dep#compilation-database.json"),
        tmp.getRootPath().relativize(compilationDatabase));

    String binaryHeaderSymlinkTreeFolder =
        String.format(
            "buck-out/gen/binary_with_dep#default,%s",
            CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    String binaryExportedHeaderSymlinkTreeFoler =
        String.format(
            "buck-out/gen/library_with_header#default,%s",
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

    assertTrue(Files.exists(tmp.getRootPath().resolve(binaryHeaderSymlinkTreeFolder)));
    assertTrue(Files.exists(tmp.getRootPath().resolve(binaryExportedHeaderSymlinkTreeFoler)));

    String libraryExportedHeaderSymlinkTreeFoler =
        String.format(
            "buck-out/gen/library_with_header#default,%s",
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

    // Verify that symlink folders for headers are created and header file is linked.
    assertTrue(Files.exists(tmp.getRootPath().resolve(libraryExportedHeaderSymlinkTreeFoler)));
    assertTrue(
        Files.exists(tmp.getRootPath().resolve(libraryExportedHeaderSymlinkTreeFoler + "/bar.h")));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseEntry.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    assertHasEntry(
        fileToEntry,
        "foo.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-I")
            .add(headerSymlinkTreeIncludePath(binaryHeaderSymlinkTreeFolder))
            .add("-I")
            .add(headerSymlinkTreeIncludePath(binaryExportedHeaderSymlinkTreeFoler))
            .addAll(EXTRA_FLAGS_FOR_HEADER_MAPS)
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add("buck-out/gen/binary_with_dep#compile-foo.cpp.o,default/foo.cpp.o")
            .add("foo.cpp")
            .build());
  }

  @Test
  public void libraryCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    Path compilationDatabase = workspace.buildAndReturnOutput(
        "//:library_with_header#default,compilation-database");
    assertEquals(
        Paths.get(
            "buck-out/gen/__library_with_header#compilation-database,default.json"),
        tmp.getRootPath().relativize(compilationDatabase));

    String headerSymlinkTreeFolder =
        String.format(
            "buck-out/gen/library_with_header#default,%s",
            CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    String exportedHeaderSymlinkTreeFoler =
        String.format(
            "buck-out/gen/library_with_header#default,%s",
            CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

    // Verify that symlink folders for headers are created.
    assertTrue(Files.exists(tmp.getRootPath().resolve(headerSymlinkTreeFolder)));
    assertTrue(Files.exists(tmp.getRootPath().resolve(exportedHeaderSymlinkTreeFoler)));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseEntry.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    assertHasEntry(
        fileToEntry,
        "bar.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-fPIC")
            .add("-fPIC")
            .add("-I")
            .add(headerSymlinkTreeIncludePath(headerSymlinkTreeFolder))
            .add("-I")
            .add(headerSymlinkTreeIncludePath(exportedHeaderSymlinkTreeFoler))
            .addAll(EXTRA_FLAGS_FOR_HEADER_MAPS)
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add("buck-out/gen/library_with_header#compile-pic-bar.cpp.o,default/bar.cpp.o")
            .add("bar.cpp")
            .build());
  }

  private static String headerSymlinkTreeIncludePath(String headerSymlinkTreePath) {
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      return headerSymlinkTreePath + ".hmap";
    } else {
      return headerSymlinkTreePath;
    }
  }

  private void assertHasEntry(
      Map<String, CxxCompilationDatabaseEntry> fileToEntry,
      String fileName,
      List<String> command) throws IOException {
    String key = tmp.getRootPath().toRealPath().resolve(fileName).toString();
    CxxCompilationDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);
    MoreAsserts.assertIterablesEquals(command, entry.args);
    assertEquals(
        Joiner.on(' ').join(
            Iterables.transform(
                command,
                Escaper.SHELL_ESCAPER)),
        entry.command);
  }
}
