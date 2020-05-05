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

package com.facebook.buck.core.model.targetgraph.impl;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WatchmanGlobPathsCheckerTest {

  private ProjectFilesystem projectFilesystem;
  private Watchman watchman;
  private Path root;

  @Rule public ExpectedException expectedException = ExpectedException.none();
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp); // set up Watchman

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(CanonicalCellName.rootCell(), tmp.getRoot());
    WatchmanFactory watchmanFactory = new WatchmanFactory();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(tmp.getRoot().toString());
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            ImmutableMap.of(),
            new TestConsole(),
            FakeClock.doNotCare(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());
  }

  @Test
  public void testCheckPathsThrowsWithNonExistingPath() {
    PathsChecker checker = new WatchmanPathsChecker(watchman, false);

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "//:a references non-existing or incorrect type of file or directory 'b'");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(ForwardRelativePath.of("b")),
        ImmutableSet.of(),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsPassesWithExistingPath() throws IOException {
    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(ForwardRelativePath.of("b")),
        ImmutableSet.of(),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsPassesWithExistingFiles() throws IOException {

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsPassesWithExistingDirectory() throws IOException {

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFolder("b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }

  @Test
  public void testCheckPathsFailedWithExistingDirectory() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "//:a references non-existing or incorrect type of file or directory 'b'");

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFolder("b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsFailedWithExistingFiles() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "//:a references non-existing or incorrect type of file or directory 'b'");

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }

  @Test
  public void testCheckPathsFailedWithCaseSensitive() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "//:a references non-existing or incorrect type of file or directory 'b'");

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("B");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsPassWithSymlink() throws IOException {
    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("b");

    FileSystemUtils.ensureSymbolicLink(root.getChild("symlink-to-regular-file"), "b");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("symlink-to-regular-file")),
        ImmutableSet.of());
  }

  @Test
  public void testCheckPathsFailedWithMultipleFiles() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "//:a references non-existing or incorrect type of file or directory 'd'");

    PathsChecker checker = new WatchmanPathsChecker(watchman, false);
    tmp.newFile("b");
    tmp.newFile("c");

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(
            ForwardRelativePath.of("b"), ForwardRelativePath.of("c"), ForwardRelativePath.of("d")),
        ImmutableSet.of());
  }

  @Test
  public void testFallbackCheckPathsFailedWithExistingFiles() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("In //:a expected directory: b");

    PathsChecker checker = new WatchmanPathsChecker(watchman, true);
    tmp.newFile("b");
    projectFilesystem.createNewFile(Paths.get("b"));

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(ForwardRelativePath.of("b")));
  }

  @Test
  public void testFallbackCheckPathsFailedWithMultipleFiles() throws IOException {
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("//:a references non-existing file or directory 'd'");

    PathsChecker checker = new WatchmanPathsChecker(watchman, true);
    tmp.newFile("b");
    tmp.newFile("c");
    projectFilesystem.createNewFile(Paths.get("b"));
    projectFilesystem.createNewFile(Paths.get("c"));

    checker.checkPaths(
        projectFilesystem,
        BuildTargetFactory.newInstance("//:a"),
        ImmutableSet.of(),
        ImmutableSet.of(
            ForwardRelativePath.of("b"), ForwardRelativePath.of("c"), ForwardRelativePath.of("d")),
        ImmutableSet.of());
  }
}
