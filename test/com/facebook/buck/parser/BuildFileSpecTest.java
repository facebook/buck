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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.filesystem.RecursiveFileMatcher;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.watchman.Capability;
import com.facebook.buck.io.watchman.FakeWatchmanClient;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class BuildFileSpecTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void recursiveVsNonRecursive() throws IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.touch(buildFile);

    Path nestedBuildFile = Paths.get("a", "b", "BUCK");
    filesystem.mkdirs(nestedBuildFile.getParent());
    filesystem.touch(nestedBuildFile);

    // Test a non-recursive spec.
    BuildFileSpec nonRecursiveSpec =
        BuildFileSpec.fromPath(buildFile.getParent(), filesystem.getRootPath());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    ImmutableSet<Path> actualBuildFiles =
        nonRecursiveSpec.findBuildFiles(
            ParserConfig.DEFAULT_BUILD_FILE_NAME,
            filesystem.asView(),
            WatchmanFactory.NULL_WATCHMAN,
            ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL,
            ImmutableSet.of());
    assertEquals(expectedBuildFiles, actualBuildFiles);

    // Test a recursive spec.
    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    expectedBuildFiles =
        ImmutableSet.of(filesystem.resolve(buildFile), filesystem.resolve(nestedBuildFile));
    actualBuildFiles =
        recursiveSpec.findBuildFiles(
            ParserConfig.DEFAULT_BUILD_FILE_NAME,
            filesystem.asView(),
            WatchmanFactory.NULL_WATCHMAN,
            ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL,
            ImmutableSet.of());
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void recursiveIgnorePaths() throws IOException, InterruptedException {
    Path ignoredBuildFile = Paths.get("a", "b", "BUCK");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.writeContentsToPath("", buildFile);

    filesystem.mkdirs(ignoredBuildFile.getParent());
    filesystem.writeContentsToPath("", ignoredBuildFile);

    // Test a recursive spec with an ignored dir.

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(
            ParserConfig.DEFAULT_BUILD_FILE_NAME,
            filesystem
                .asView()
                .withView(
                    Paths.get(""), ImmutableSet.of(RecursiveFileMatcher.of(ignoredBuildFile))),
            WatchmanFactory.NULL_WATCHMAN,
            ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL,
            ImmutableSet.of());
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void findWithWatchmanSucceeds() throws IOException, InterruptedException {
    Path watchRoot = Paths.get(".").toAbsolutePath().normalize();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(watchRoot.resolve("project-name"));
    Path buildFile = Paths.get("a", "BUCK");

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    FakeWatchmanClient fakeWatchmanClient =
        new FakeWatchmanClient(
            0,
            ImmutableMap.of(
                ImmutableList.of(
                    "query",
                    watchRoot.toString(),
                    ImmutableMap.of(
                        "relative_root", "project-name",
                        "sync_timeout", 0,
                        "path", ImmutableList.of("a"),
                        "fields", ImmutableList.of("name"),
                        "expression",
                            ImmutableList.of(
                                "allof",
                                "exists",
                                ImmutableList.of("name", "BUCK"),
                                ImmutableList.of("type", "f")))),
                ImmutableMap.of("files", ImmutableList.of("a/BUCK"))));
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(
            ParserConfig.DEFAULT_BUILD_FILE_NAME,
            filesystem.asView(),
            createWatchman(fakeWatchmanClient, filesystem, watchRoot),
            ParserConfig.BuildFileSearchMethod.WATCHMAN,
            ImmutableSet.of());
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void findWithWatchmanFallsBackToFilesystemOnTimeout()
      throws IOException, InterruptedException {
    Path watchRoot = Paths.get(".").toAbsolutePath().normalize();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(watchRoot.resolve("project-name"));
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.touch(buildFile);

    Path nestedBuildFile = Paths.get("a", "b", "BUCK");
    filesystem.mkdirs(nestedBuildFile.getParent());
    filesystem.touch(nestedBuildFile);

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    FakeWatchmanClient timingOutWatchmanClient =
        new FakeWatchmanClient(
            // Pretend the query takes a very very long time.
            TimeUnit.SECONDS.toNanos(Long.MAX_VALUE),
            ImmutableMap.of(
                ImmutableList.of(
                    "query",
                    watchRoot.toString(),
                    ImmutableMap.of(
                        "relative_root", "project-name",
                        "sync_timeout", 0,
                        "path", ImmutableList.of("a"),
                        "fields", ImmutableList.of("name"),
                        "expression",
                            ImmutableList.of(
                                "allof",
                                "exists",
                                ImmutableList.of("name", "BUCK"),
                                ImmutableList.of("type", "f")))),
                ImmutableMap.of("files", ImmutableList.of("a/BUCK", "a/b/BUCK"))));
    ImmutableSet<Path> expectedBuildFiles =
        ImmutableSet.of(filesystem.resolve(buildFile), filesystem.resolve(nestedBuildFile));
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(
            ParserConfig.DEFAULT_BUILD_FILE_NAME,
            filesystem.asView(),
            createWatchman(timingOutWatchmanClient, filesystem, watchRoot),
            ParserConfig.BuildFileSearchMethod.WATCHMAN,
            ImmutableSet.of());
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void testWildcardFolderNotFound() throws IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(filesystem.resolve("foo/bar"), filesystem.getRootPath());
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("could not be found");
    recursiveSpec.findBuildFiles(
        ParserConfig.DEFAULT_BUILD_FILE_NAME,
        filesystem.asView(),
        WatchmanFactory.NULL_WATCHMAN,
        ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL,
        ImmutableSet.of());
  }

  /**
   * Test that ignored folders work if using explicit filtering and not capabilities of {@link
   * ProjectFilesystemView}
   */
  @Test
  public void ignoredFoldersAreNotTraversedIfPassedExplicitly()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath());

    Path ignoredBuildFile = Paths.get("a", "b", "BUCK");
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.writeContentsToPath("", buildFile);

    filesystem.mkdirs(ignoredBuildFile.getParent());
    filesystem.writeContentsToPath("", ignoredBuildFile);

    // Test a recursive spec with an ignored dir.

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(
            "BUCK",
            filesystem.asView().withView(Paths.get(""), ImmutableSet.of()),
            WatchmanFactory.NULL_WATCHMAN,
            ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL,
            ImmutableSet.of(RecursiveFileMatcher.of(ignoredBuildFile.getParent())));
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  private static Watchman createWatchman(
      WatchmanClient client, ProjectFilesystem filesystem, Path watchRoot) {
    return new Watchman(
        ImmutableMap.of(
            filesystem.getRootPath(),
            ProjectWatch.of(watchRoot.toString(), Optional.of("project-name"))),
        ImmutableSet.of(
            Capability.SUPPORTS_PROJECT_WATCH, Capability.DIRNAME, Capability.WILDMATCH_GLOB),
        ImmutableMap.of(),
        Optional.of(Paths.get(".watchman-sock"))) {
      @Override
      public WatchmanClient createClient() {
        return client;
      }
    };
  }
}
