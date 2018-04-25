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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.FakeWatchmanClient;
import com.facebook.buck.io.ProjectWatch;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanClient;
import com.facebook.buck.io.WatchmanFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
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
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ImmutableSet<Path> actualBuildFiles =
        nonRecursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);
    assertEquals(expectedBuildFiles, actualBuildFiles);

    // Test a recursive spec.
    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    expectedBuildFiles =
        ImmutableSet.of(filesystem.resolve(buildFile), filesystem.resolve(nestedBuildFile));
    actualBuildFiles =
        recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void recursiveIgnorePaths() throws IOException, InterruptedException {
    Path ignoredBuildFile = Paths.get("a", "b", "BUCK");
    Config config = ConfigBuilder.createFromText("[project]", "ignore = a/b");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath(), config);
    Path buildFile = Paths.get("a", "BUCK");
    filesystem.mkdirs(buildFile.getParent());
    filesystem.writeContentsToPath("", buildFile);

    filesystem.mkdirs(ignoredBuildFile.getParent());
    filesystem.writeContentsToPath("", ignoredBuildFile);

    // Test a recursive spec with an ignored dir.

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
    ImmutableSet<Path> expectedBuildFiles = ImmutableSet.of(filesystem.resolve(buildFile));
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);
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
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(filesystem)
            .setWatchman(createWatchman(fakeWatchmanClient, filesystem, watchRoot))
            .build();
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.WATCHMAN);
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void findWithWatchmanThrowsOnFailure() throws IOException, InterruptedException {
    Path watchRoot = Paths.get(".").toAbsolutePath().normalize();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(watchRoot.resolve("project-name"));
    Path buildFile = Paths.get("a", "BUCK");

    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(buildFile.getParent(), filesystem.getRootPath());
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
                ImmutableMap.of("files", ImmutableList.of("a/BUCK"))),
            new IOException("Whoopsie!"));
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(filesystem)
            .setWatchman(createWatchman(fakeWatchmanClient, filesystem, watchRoot))
            .build();

    thrown.expect(IOException.class);
    thrown.expectMessage("Whoopsie!");
    recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.WATCHMAN);
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
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(filesystem)
            .setWatchman(createWatchman(timingOutWatchmanClient, filesystem, watchRoot))
            .build();
    ImmutableSet<Path> expectedBuildFiles =
        ImmutableSet.of(filesystem.resolve(buildFile), filesystem.resolve(nestedBuildFile));
    ImmutableSet<Path> actualBuildFiles =
        recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.WATCHMAN);
    assertEquals(expectedBuildFiles, actualBuildFiles);
  }

  @Test
  public void testWildcardFolderNotFound() throws IOException, InterruptedException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    BuildFileSpec recursiveSpec =
        BuildFileSpec.fromRecursivePath(filesystem.resolve("foo/bar"), filesystem.getRootPath());
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("could not be found");
    recursiveSpec.findBuildFiles(cell, ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL);
  }

  private static Watchman createWatchman(
      WatchmanClient client, ProjectFilesystem filesystem, Path watchRoot) {
    return new Watchman(
        ImmutableMap.of(
            filesystem.getRootPath(),
            ProjectWatch.of(watchRoot.toString(), Optional.of("project-name"))),
        ImmutableSet.of(
            WatchmanFactory.Capability.SUPPORTS_PROJECT_WATCH,
            WatchmanFactory.Capability.DIRNAME,
            WatchmanFactory.Capability.WILDMATCH_GLOB),
        ImmutableMap.of(),
        Optional.of(Paths.get(".watchman-sock"))) {
      @Override
      public WatchmanClient createClient() {
        return client;
      }
    };
  }
}
