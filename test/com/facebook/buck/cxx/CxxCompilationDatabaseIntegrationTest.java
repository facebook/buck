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

import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
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

import org.junit.Before;
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

  private ProjectWorkspace workspace;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void initializeWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    // cxx_test requires gtest_dep to be set
    workspace.writeContentsToPath("[cxx]\ngtest_dep = //:fake-gtest", ".buckconfig");
  }

  @Test
  public void binaryWithDependenciesCompilationDatabase() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_dep#compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    assertTrue(Files.exists(rootPath.resolve(binaryHeaderSymlinkTreeFolder)));

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:library_with_header");
    Path libraryExportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            libraryTarget.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    // Verify that symlink folders for headers are created and header file is linked.
    assertTrue(Files.exists(rootPath.resolve(libraryExportedHeaderSymlinkTreeFolder)));
    assertTrue(
        Files.exists(rootPath.resolve(libraryExportedHeaderSymlinkTreeFolder + "/bar.h")));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    assertHasEntry(
        fileToEntry,
        "foo.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-I")
            .add(headerSymlinkTreeIncludePath(binaryHeaderSymlinkTreeFolder))
            .add("-I")
            .add(headerSymlinkTreeIncludePath(libraryExportedHeaderSymlinkTreeFolder))
            .addAll(getExtraFlagsForHeaderMaps())
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        target.withFlavors(
                            ImmutableFlavor.of("default"),
                            ImmutableFlavor.of("compile-" + sanitize("foo.cpp.o"))),
                        "%s/foo.cpp.o")
                    .toString())
            .add(rootPath.resolve(Paths.get("foo.cpp")).toRealPath().toString())
            .build());
  }

  @Test
  public void libraryCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path headerSymlinkTreeFolder =
        BuildTargets.getGenPath(
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path exportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    // Verify that symlink folders for headers are created.
    assertTrue(Files.exists(rootPath.resolve(headerSymlinkTreeFolder)));
    assertTrue(Files.exists(rootPath.resolve(exportedHeaderSymlinkTreeFolder)));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
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
            .add(headerSymlinkTreeIncludePath(exportedHeaderSymlinkTreeFolder))
            .addAll(getExtraFlagsForHeaderMaps())
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        target.withFlavors(
                            ImmutableFlavor.of("default"),
                            ImmutableFlavor.of("compile-pic-" + sanitize("bar.cpp.o"))),
                        "%s/bar.cpp.o")
                    .toString())
            .add(rootPath.resolve(Paths.get("bar.cpp")).toRealPath().toString())
            .build());
  }

  @Test
  public void testCompilationDatabase() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:test#default,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path binaryExportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    assertHasEntry(
        fileToEntry,
        "test.cpp",
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-fPIC")
            .add("-fPIC")
            .add("-I")
            .add(headerSymlinkTreeIncludePath(binaryHeaderSymlinkTreeFolder))
            .add("-I")
            .add(headerSymlinkTreeIncludePath(binaryExportedHeaderSymlinkTreeFolder))
            .addAll(getExtraFlagsForHeaderMaps())
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        target.withFlavors(
                            ImmutableFlavor.of("default"),
                            ImmutableFlavor.of("compile-pic-" + sanitize("test.cpp.o"))),
                        "%s/test.cpp.o")
                    .toString())
            .add(rootPath.resolve(Paths.get("test.cpp")).toRealPath().toString())
            .build());
  }

  private String headerSymlinkTreeIncludePath(Path headerSymlinkTreePath) throws IOException {
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      return String.format("%s.hmap", headerSymlinkTreePath);
    } else {
      return String.format("%s", headerSymlinkTreePath);
    }
  }

  private void assertHasEntry(
      Map<String, CxxCompilationDatabaseEntry> fileToEntry,
      String fileName,
      List<String> command) throws IOException {
    String key = tmp.getRootPath().toRealPath().resolve(fileName).toString();
    CxxCompilationDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);
    assertEquals(
        Joiner.on(' ').join(
            Iterables.transform(
                command,
                Escaper.SHELL_ESCAPER)),
        entry.getCommand());
  }

  private ImmutableList<String> getExtraFlagsForHeaderMaps() throws IOException {
    // This works around OS X being amusing about the location of temp directories.
    return PREPROCESSOR_SUPPORTS_HEADER_MAPS ?
        ImmutableList.of("-I", BuckConstant.BUCK_OUTPUT_DIRECTORY) :
        ImmutableList.<String>of();
  }
}
