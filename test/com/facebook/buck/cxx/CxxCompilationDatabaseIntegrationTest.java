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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
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
import java.util.ArrayList;
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
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(filesystem, target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    assertTrue(Files.exists(rootPath.resolve(binaryHeaderSymlinkTreeFolder)));

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:library_with_header");
    Path libraryExportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
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
            .add(headerSymlinkTreePath(binaryHeaderSymlinkTreeFolder).toString())
            .add("-I")
            .add(headerSymlinkTreePath(libraryExportedHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        filesystem,
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
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(filesystem, target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path headerSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path exportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
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
            .add(headerSymlinkTreePath(headerSymlinkTreeFolder).toString())
            .add("-I")
            .add(headerSymlinkTreePath(exportedHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        filesystem,
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
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRootPath();
    assertEquals(
        BuildTargets.getGenPath(filesystem, target, "__%s.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path binaryExportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
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
            .add(headerSymlinkTreePath(binaryHeaderSymlinkTreeFolder).toString())
            .add("-I")
            .add(headerSymlinkTreePath(binaryExportedHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-x")
            .add("c++")
            .add("-c")
            .add("-o")
            .add(
                BuildTargets
                    .getGenPath(
                        filesystem,
                        target.withFlavors(
                            ImmutableFlavor.of("default"),
                            ImmutableFlavor.of("compile-pic-" + sanitize("test.cpp.o"))),
                        "%s/test.cpp.o")
                    .toString())
            .add(rootPath.resolve(Paths.get("test.cpp")).toRealPath().toString())
            .build());
  }

  @Test
  public void compilationDatabaseFetchedFromCacheAlsoFetchesSymlinkTreeOrHeaderMap()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // This test only fails if the directory cache is enabled and we don't update
    // the header map/symlink tree correctly when fetching from the cache.
    workspace.enableDirCache();

    addLibraryHeaderFiles(workspace);

    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");

    // Populate the cache with the built rule
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Path headerSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path exportedHeaderSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("default"),
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    // Validate the symlink tree/header maps
    verifyHeaders(
        workspace,
        headerSymlinkTreeFolder,
        "bar.h",
        "baz.h",
        "blech_private.h");
    verifyHeaders(
        workspace,
        exportedHeaderSymlinkTreeFolder,
        "bar.h",
        "baz.h");

    // Delete the newly-added files and build again
    Files.delete(workspace.getPath("baz.h"));
    Files.delete(workspace.getPath("blech_private.h"));
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(
        workspace,
        headerSymlinkTreeFolder,
        "bar.h");
    verifyHeaders(
        workspace,
        exportedHeaderSymlinkTreeFolder,
        "bar.h");

    // Restore the headers, build again, and check the symlink tree/header maps
    addLibraryHeaderFiles(workspace);
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(
        workspace,
        headerSymlinkTreeFolder,
        "bar.h",
        "baz.h",
        "blech_private.h");
    verifyHeaders(
        workspace,
        exportedHeaderSymlinkTreeFolder,
        "bar.h",
        "baz.h");
  }

  @Test
  public void compilationDatabaseWithDepsFetchedFromCacheAlsoFetchesSymlinkTreeOrHeaderMapOfDeps()
      throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "compilation_database_with_deps", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // This test only fails if the directory cache is enabled and we don't update
    // the header map/symlink tree correctly when fetching from the cache.
    workspace.enableDirCache();

    addDepLibraryHeaderFiles(workspace);

    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");

    // Populate the cache with the built rule
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Path dep1ExportedSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            BuildTargetFactory.newInstance("//dep1:dep1#default,headers"),
            "%s");
    Path dep2ExportedSymlinkTreeFolder =
        BuildTargets.getGenPath(
            filesystem,
            BuildTargetFactory.newInstance("//dep2:dep2#default,headers"),
            "%s");

    // Validate the deps' symlink tree/header maps
    verifyHeaders(
        workspace,
        dep1ExportedSymlinkTreeFolder,
        "dep1/dep1.h",
        "dep1/dep1_new.h");
    verifyHeaders(
        workspace,
        dep2ExportedSymlinkTreeFolder,
        "dep2/dep2.h",
        "dep2/dep2_new.h");

    // Delete the newly-added files and build again
    Files.delete(workspace.getPath("dep1/dep1_new.h"));
    Files.delete(workspace.getPath("dep2/dep2_new.h"));
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(
        workspace,
        dep1ExportedSymlinkTreeFolder,
        "dep1/dep1.h");
    verifyHeaders(
        workspace,
        dep2ExportedSymlinkTreeFolder,
        "dep2/dep2.h");

    // Restore the headers, build again, and check the deps' symlink tree/header maps
    addDepLibraryHeaderFiles(workspace);
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(
        workspace,
        dep1ExportedSymlinkTreeFolder,
        "dep1/dep1.h",
        "dep1/dep1_new.h");
    verifyHeaders(
        workspace,
        dep2ExportedSymlinkTreeFolder,
        "dep2/dep2.h",
        "dep2/dep2_new.h");
  }

  private void addLibraryHeaderFiles(ProjectWorkspace workspace) throws IOException {
    // These header files are included in //:library_with_header via a glob
    workspace.writeContentsToPath("// Hello world\n", "baz.h");
    workspace.writeContentsToPath("// Hello private world\n", "blech_private.h");
  }

  private void addDepLibraryHeaderFiles(ProjectWorkspace workspace) throws IOException {
    workspace.writeContentsToPath("// Hello dep1 world\n", "dep1/dep1_new.h");
    workspace.writeContentsToPath("// Hello dep2 world\n", "dep2/dep2_new.h");
  }

  private void verifyHeaders(
      ProjectWorkspace projectWorkspace,
      Path path,
      String... headers) throws IOException {
    Path resolvedPath = headerSymlinkTreePath(path);
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      final List<String> presentHeaders = new ArrayList<>();
      HeaderMap headerMap = HeaderMap.loadFromFile(
          projectWorkspace.getPath(resolvedPath).toFile());
      headerMap.visit(new HeaderMap.HeaderMapVisitor() {
          @Override
          public void apply(String str, String prefix, String suffix) {
            presentHeaders.add(str);
          }
      });
      assertThat(presentHeaders, containsInAnyOrder(headers));
    } else {
      for (String header : headers) {
        assertThat(
            Files.exists(projectWorkspace.getPath(resolvedPath.resolve(header))),
            is(true));
      }
    }
  }

  private Path headerSymlinkTreePath(Path path) throws IOException {
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      return path.resolveSibling(String.format("%s.hmap", path.getFileName()));
    } else {
      return path;
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

  private ImmutableList<String> getExtraFlagsForHeaderMaps(ProjectFilesystem filesystem)
      throws IOException {
    // This works around OS X being amusing about the location of temp directories.
    return PREPROCESSOR_SUPPORTS_HEADER_MAPS ?
        ImmutableList.of("-I", filesystem.getBuckPaths().getBuckOut().toString()) :
        ImmutableList.<String>of();
  }
}
