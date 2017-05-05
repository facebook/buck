/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.autosparse;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystemDelegate;
import com.facebook.buck.io.ProjectFilesystemDelegateFactory;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.facebook.buck.zip.Unzip;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AutoSparseIntegrationTest {
  /*
   * Tests against a sparse repository containing the tree:
   *
   * | file1 x
   * | file2
   * | file3 -> file2
   * | not_hidden *
   * | sparse_profile *
   * |
   * + subdir
   * |   |
   * |   + file_in_subdir
   * |
   * * not_hidden_subdir
   *     |
   *     + file_in_subdir_not_hidden
   *
   * All files are hidden by the sparse profile except for files marked with an asterisk. Files with
   * an x behind them are executable.
   *
   * Test requires the hg sparse extension to be available.
   */

  private static final String HG_REPO_ZIP = "hg_repo.zip";
  private static final String REPO_DIR = "sparse_repo";

  private static Path repoPath;

  @SuppressWarnings("javadoc")
  @ClassRule
  public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static HgCmdLineInterface repoCmdline;

  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {
    repoPath = explodeRepoZip();
    repoCmdline =
        new HgCmdLineInterface(
            new TestProcessExecutorFactory(),
            repoPath,
            new VersionControlBuckConfig(FakeBuckConfig.builder().build()).getHgCmd(),
            ImmutableMap.of());
  }

  @Before
  public void setUp() throws InterruptedException {
    assumeHgInstalled();
    assumeHgSparseInstalled();
  }

  @After
  public void tearDown() {
    AbstractAutoSparseFactory.perSCRoot.clear();
  }

  @Test
  public void testAutosparseDisabled() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate(repoPath, false, ImmutableList.of());
    Assume.assumeFalse(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testAutosparseEnabledHgSubdir() throws InterruptedException {
    ProjectFilesystemDelegate delegate =
        createDelegate(repoPath.resolve("not_hidden_subdir"), true, ImmutableList.of());
    Assume.assumeTrue(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testAutosparseEnabledNotHgDir() throws InterruptedException {
    ProjectFilesystemDelegate delegate =
        createDelegate(repoPath.getParent(), true, ImmutableList.of());
    Assume.assumeFalse(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testRelativePath() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Path path = delegate.getPathForRelativePath(Paths.get("subdir/file_in_subdir"));
    Assert.assertEquals(path, repoPath.resolve("subdir/file_in_subdir"));
  }

  @Test
  public void testExecutableFile() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.isExecutable(repoPath.resolve("file1")));
  }

  @Test
  public void testNotExecutableFile() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isExecutable(repoPath.resolve("file2")));
  }

  @Test
  public void testExecutableNotExisting() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isExecutable(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testSymlink() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.isSymlink(repoPath.resolve("file3")));
  }

  @Test
  public void testNotSymLink() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isSymlink(repoPath.resolve("file2")));
  }

  @Test
  public void testSymlinkNotExisting() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isSymlink(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testExists() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.exists(repoPath.resolve("file1")));
  }

  @Test
  public void testNotExists() throws InterruptedException {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.exists(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testExistsUntracked() throws InterruptedException, IOException {
    ProjectFilesystemDelegate delegate = createDelegate();
    File newFile = repoPath.resolve("newFile").toFile();
    new FileOutputStream(newFile).close();
    Assume.assumeTrue(delegate.exists(newFile.toPath()));
  }

  @Test
  public void testMaterialize() throws InterruptedException, IOException {
    ProjectFilesystemDelegate delegate = createDelegate(repoPath, true, ImmutableList.of("subdir"));
    // Touch various files, these should be part of the profile
    delegate.exists(repoPath.resolve("file1"));
    delegate.exists(repoPath.resolve("file2"));
    // Only directly include the file, not the parent dir
    delegate.exists(repoPath.resolve("subdir/file_in_subdir"));
    // Only include the parent directory, not the file
    delegate.exists(repoPath.resolve("not_hidden_subdir/file_in_subdir_not_hidden"));

    BuckEventBus eventBus = BuckEventBusFactory.newInstance(new FakeClock(0));
    AutoSparseIntegrationTest.CapturingAutoSparseStateEventListener listener =
        new AutoSparseIntegrationTest.CapturingAutoSparseStateEventListener();
    eventBus.register(listener);
    delegate.ensureConcreteFilesExist(eventBus);

    List<String> lines =
        Files.readAllLines(
            repoPath.resolve(".hg/sparse"),
            Charset.forName(System.getProperty("file.encoding", "UTF-8")));
    List<String> expected =
        ImmutableList.of(
            "%include sparse_profile",
            "[include]",
            "file1",
            "file2",
            "not_hidden_subdir",
            "subdir/file_in_subdir",
            "[exclude]",
            "" // sparse always writes a newline at the end
            );
    Assert.assertEquals(expected, lines);

    // assert we got events with a matching summary
    List<AutoSparseStateEvents> events = listener.getLoggedEvents();
    Assert.assertEquals(events.size(), 2);
    Assert.assertTrue(events.get(0) instanceof AutoSparseStateEvents.SparseRefreshStarted);
    Assert.assertTrue(events.get(1) instanceof AutoSparseStateEvents.SparseRefreshFinished);
    SparseSummary summary = ((AutoSparseStateEvents.SparseRefreshFinished) events.get(1)).summary;
    Assert.assertEquals(summary.getProfilesAdded(), 0);
    Assert.assertEquals(summary.getIncludeRulesAdded(), 4);
    Assert.assertEquals(summary.getExcludeRulesAdded(), 0);
    Assert.assertEquals(summary.getFilesAdded(), 3);
    Assert.assertEquals(summary.getFilesDropped(), 0);
    Assert.assertEquals(summary.getFilesConflicting(), 0);
  }

  private static void assumeHgInstalled() throws InterruptedException {
    // If Mercurial is not installed on the build box, then skip tests.
    try {
      repoCmdline.currentRevisionId();
    } catch (VersionControlCommandFailedException ex) {
      Assume.assumeNoException(ex);
    }
  }

  private static void assumeHgSparseInstalled() {
    // If hg sparse throws an exception, then skip tests.
    Throwable exception = null;
    try {
      Path exportFile = Files.createTempFile("buck_autosparse_rules", "");
      try (Writer writer = new BufferedWriter(new FileWriter(exportFile.toFile()))) {
        writer.write("[include]\n"); // deliberately mostly empty
      }
      repoCmdline.exportHgSparseRules(exportFile);
    } catch (VersionControlCommandFailedException | InterruptedException | IOException e) {
      exception = e;
    }
    Assume.assumeNoException(exception);
  }

  private static ProjectFilesystemDelegate createDelegate() throws InterruptedException {
    return createDelegate(repoPath, true, ImmutableList.of());
  }

  private static ProjectFilesystemDelegate createDelegate(
      Path root, boolean enableAutosparse, ImmutableList<String> autosparseIgnore)
      throws InterruptedException {
    String hgCmd = new VersionControlBuckConfig(FakeBuckConfig.builder().build()).getHgCmd();
    return ProjectFilesystemDelegateFactory.newInstance(
        root, hgCmd, AutoSparseConfig.of(enableAutosparse, autosparseIgnore));
  }

  private static Path explodeRepoZip() throws InterruptedException, IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(AutoSparseIntegrationTest.class);
    // Use a real path to resolve funky symlinks (I'm looking at you, OS X).
    Path destination = tempFolder.getRoot().toPath().toRealPath();
    Path hgRepoZipPath = testDataDir.resolve(HG_REPO_ZIP);
    Path hgRepoZipCopyPath = destination.resolve(HG_REPO_ZIP);

    Path repoPath = destination.resolve(REPO_DIR);
    Files.copy(hgRepoZipPath, hgRepoZipCopyPath, REPLACE_EXISTING);

    Unzip.extractZipFile(
        hgRepoZipCopyPath, repoPath, Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    return repoPath;
  }

  public static class CapturingAutoSparseStateEventListener {
    private final List<AutoSparseStateEvents> logEvents = new ArrayList<>();

    @Subscribe
    public void logEvent(AutoSparseStateEvents event) {
      logEvents.add(event);
    }

    public List<AutoSparseStateEvents> getLoggedEvents() {
      return logEvents;
    }
  }
}
