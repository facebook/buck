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
import com.facebook.buck.io.ProjectFilesystemDelegate;
import com.facebook.buck.io.ProjectFilesystemDelegateFactory;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.DefaultVersionControlCmdLineInterfaceFactory;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.facebook.buck.zip.Unzip;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.After;
import org.junit.Assume;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

  private static VersionControlCmdLineInterface repoCmdline;

  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {
    repoPath = explodeRepoZip();

    DefaultVersionControlCmdLineInterfaceFactory vcFactory =
        new DefaultVersionControlCmdLineInterfaceFactory(
            repoPath,
            new TestProcessExecutorFactory(),
            new VersionControlBuckConfig(FakeBuckConfig.builder().build()),
            ImmutableMap.of());
    repoCmdline = vcFactory.createCmdLineInterface();
  }

  @Before
  public void setUp() {
    assumeHgInstalled();
    assumeHgSparseInstalled();
  }

  @After
  public void tearDown() {
    AbstractAutoSparseFactory.perSCRoot.clear();
  }

  @Test
  public void testAutosparseDisabled() {
    ProjectFilesystemDelegate delegate = createDelegate(
        repoPath, false, ImmutableList.of(), Optional.empty());
    Assume.assumeFalse(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testAutosparseEnabledHgSubdir() {
    ProjectFilesystemDelegate delegate = createDelegate(
        repoPath.resolve("not_hidden_subdir"), true, ImmutableList.of(), Optional.empty());
    Assume.assumeTrue(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testAutosparseEnabledNotHgDir() {
    ProjectFilesystemDelegate delegate = createDelegate(
        repoPath.getParent(), true, ImmutableList.of(), Optional.empty());
    Assume.assumeFalse(delegate instanceof AutoSparseProjectFilesystemDelegate);
  }

  @Test
  public void testRelativePath() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Path path = delegate.getPathForRelativePath(Paths.get("subdir/file_in_subdir"));
    Assert.assertEquals(path, repoPath.resolve("subdir/file_in_subdir"));
  }

  @Test
  public void testExecutableFile() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.isExecutable(repoPath.resolve("file1")));
  }

  @Test
  public void testNotExecutableFile() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isExecutable(repoPath.resolve("file2")));
  }

  @Test
  public void testExecutableNotExisting() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isExecutable(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testSymlink() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.isSymlink(repoPath.resolve("file3")));
  }

  @Test
  public void testNotSymLink() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isSymlink(repoPath.resolve("file2")));
  }

  @Test
  public void testSymlinkNotExisting() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.isSymlink(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testExists() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeTrue(delegate.exists(repoPath.resolve("file1")));
  }

  @Test
  public void testNotExists() {
    ProjectFilesystemDelegate delegate = createDelegate();
    Assume.assumeFalse(delegate.exists(repoPath.resolve("nonsuch")));
  }

  @Test
  public void testExistsUntracked() throws FileNotFoundException, IOException {
    ProjectFilesystemDelegate delegate = createDelegate();
    File newFile = repoPath.resolve("newFile").toFile();
    new FileOutputStream(newFile).close();
    Assume.assumeTrue(delegate.exists(newFile.toPath()));
  }

  @Test
  public void testMaterialize() throws IOException {
    ProjectFilesystemDelegate delegate = createDelegate(
        repoPath, true, ImmutableList.of("subdir"), Optional.of("sparse_profile")
    );
    // Touch various files, these should be part of the profile
    delegate.exists(repoPath.resolve("file1"));
    delegate.exists(repoPath.resolve("file2"));
    // Only directly include the file, not the parent dir
    delegate.exists(repoPath.resolve("subdir/file_in_subdir"));
    // Only include the parent directory, not the file
    delegate.exists(repoPath.resolve("not_hidden_subdir/file_in_subdir_not_hidden"));

    delegate.ensureConcreteFilesExist();

    List<String> lines = Files.readAllLines(
        repoPath.resolve(".hg/sparse"),
        Charset.forName(System.getProperty("file.encoding", "UTF-8"))
    );
    // sort to mitigate non-ordered nature of sets
    Collections.sort(lines);
    List<String> expected = ImmutableList.of(
        "%include sparse_profile",
        "[include]",
        "file1",
        "file2",
        "not_hidden_subdir",
        "subdir/file_in_subdir"
    );
    Assert.assertEquals(expected, lines);
  }

  private static void assumeHgInstalled() {
    // If Mercurial is not installed on the build box, then skip tests.
    Assume.assumeTrue(repoCmdline instanceof HgCmdLineInterface);
  }

  private static void assumeHgSparseInstalled() {
    // If hg sparse throws an exception, then skip tests.
    Throwable exception = null;
    try {
      ((HgCmdLineInterface) repoCmdline).refreshHgSparse();
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      exception = e;
    }
    Assume.assumeNoException(exception);
  }

  private static ProjectFilesystemDelegate createDelegate() {
    return createDelegate(repoPath, true, ImmutableList.of(), Optional.empty());
  }

  private static ProjectFilesystemDelegate createDelegate(
      Path root,
      boolean enableAutosparse,
      ImmutableList<String> autosparseIgnore,
      Optional<String> autosparseBaseProfile) {
    String hgCmd = new VersionControlBuckConfig(
        FakeBuckConfig.builder().build()).getHgCmd();
    return ProjectFilesystemDelegateFactory.newInstance(
        root, hgCmd, enableAutosparse, autosparseIgnore, autosparseBaseProfile);
  }

  private static Path explodeRepoZip() throws IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(AutoSparseIntegrationTest.class);
    // Use a real path to resolve funky symlinks (I'm looking at you, OS X).
    Path destination = tempFolder.getRoot().toPath().toRealPath();
    Path hgRepoZipPath = testDataDir.resolve(HG_REPO_ZIP);
    Path hgRepoZipCopyPath = destination.resolve(HG_REPO_ZIP);

    Path repoPath = destination.resolve(REPO_DIR);
    Files.copy(hgRepoZipPath, hgRepoZipCopyPath, REPLACE_EXISTING);

    Unzip.extractZipFile(
        hgRepoZipCopyPath,
        repoPath,
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    return repoPath;
  }
}
