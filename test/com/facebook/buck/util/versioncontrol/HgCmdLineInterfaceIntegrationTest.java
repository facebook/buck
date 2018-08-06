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

package com.facebook.buck.util.versioncontrol;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class HgCmdLineInterfaceIntegrationTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  private static final String HG_REPOS_ZIP = "hg_repos.zip";
  private static final String REPOS_DIR = "hg_repos";
  private static final String REPO_TWO_DIR = "hg_repo_two";
  private static final String REPO_THREE_DIR = "hg_repo_three";

  /**
   * *
   *
   * <p>Test data:
   *
   * <p>The following repo is used in the tests:
   *
   * <pre>
   * @  e1b8ef  branch_from_master3
   * |  diverge from master_3 further
   * |
   * @  00618f
   * |  diverge from master_3
   * |
   * o  90e0a6  master3
   * |  commit3
   * |
   * | o  65b545  branch_from_master2
   * |/   diverge from master_2
   * |
   * o  8b1b79  master2
   * |  commit2
   * |
   * o  f5091a  master1
   * commit1
   * </pre>
   *
   * There are two different variants (both stored inside HG_REPOS_ZIP):
   *
   * <p>hg_repo_two: above, current tip @branch_from_master2, and no local changes. hg_repo_three:
   * above, current tip @branch_from_master3, and with local changes.
   */
  @SuppressWarnings("javadoc")
  @ClassRule
  public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static VersionControlCmdLineInterface repoTwoCmdLine;
  private static VersionControlCmdLineInterface repoThreeCmdLine;
  private static Path reposPath;

  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {
    reposPath = explodeReposZip();

    repoTwoCmdLine = makeCmdLine(reposPath.resolve(REPO_TWO_DIR));
    repoThreeCmdLine = makeCmdLine(reposPath.resolve(REPO_THREE_DIR));
  }

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(repoTwoCmdLine.isSupportedVersionControlSystem());
  }

  @Test
  public void whenWorkingDirectoryUnchangedThenHasWorkingDirectoryChangesReturnsFalse()
      throws VersionControlCommandFailedException, InterruptedException {
    assertThat(repoTwoCmdLine.changedFiles("."), hasSize(0));
  }

  @Test
  public void whenWorkingDirectoryChangedThenHasWorkingDirectoryChangesReturnsTrue()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals(
        ImmutableSet.of("A tracked_change", "? local_change"), repoThreeCmdLine.changedFiles("."));
  }

  @Test
  public void testChangedFilesFromHead()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals(
        ImmutableSet.of("A tracked_change", "? local_change"), repoThreeCmdLine.changedFiles("."));
  }

  @Test
  public void testChangedFilesFromCommonAncestor()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableSet<String> changedFiles = repoThreeCmdLine.changedFiles("ancestor(., master3)");
    assertThat(
        changedFiles,
        Matchers.containsInAnyOrder(
            "A tracked_change", "A change3", "A change3-2", "? local_change"));
  }

  @Test
  public void testDiffBetweenTheSameRevision()
      throws VersionControlCommandFailedException, InterruptedException {
    exception.expect(VersionControlCommandFailedException.class);
    repoThreeCmdLine.diffBetweenRevisions("adf7a0", "adf7a0").get();
  }

  @Test
  public void testDiffBetweenDiffs()
      throws VersionControlCommandFailedException, InterruptedException, IOException {
    ImmutableList<String> expectedValue =
        ImmutableList.of(
            "# HG changeset patch",
            "# User Joe Blogs <joe.blogs@fb.com>",
            "# Date 1440589545 -3600",
            "#      Wed Aug 26 12:45:45 2015 +0100",
            "# Node ID 2911b3cab6b24374a3649ebb96b0e53324e9c02e",
            "# Parent  b1fd7e5896af8aa30e3e797ef1445605eec6d055",
            "diverge from master_2",
            "",
            "diff --git a/change2 b/change2",
            "new file mode 100644",
            "");
    try (InputStream diffFileStream =
        repoThreeCmdLine.diffBetweenRevisions("b1fd7e", "2911b3").get()) {
      InputStreamReader diffFileReader = new InputStreamReader(diffFileStream, Charsets.UTF_8);
      String actualDiff = CharStreams.toString(diffFileReader);
      assertEquals(String.join("\n", expectedValue), actualDiff);
    }
  }

  private static Path explodeReposZip() throws InterruptedException, IOException {
    return explodeReposZip(tempFolder.getRoot().toPath());
  }

  private static Path explodeReposZip(Path destination) throws InterruptedException, IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(HgCmdLineInterfaceIntegrationTest.class);
    Path hgRepoZipPath = testDataDir.resolve(HG_REPOS_ZIP);

    Path hgRepoZipCopyPath = destination.resolve(HG_REPOS_ZIP);

    Files.copy(hgRepoZipPath, hgRepoZipCopyPath, REPLACE_EXISTING);

    Path reposPath = destination.resolve(REPOS_DIR);

    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            hgRepoZipCopyPath,
            reposPath,
            ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    return reposPath;
  }

  private static VersionControlCmdLineInterface makeCmdLine(Path repoRootDir) {
    return new DelegatingVersionControlCmdLineInterface(
        repoRootDir,
        new TestProcessExecutorFactory(),
        new VersionControlBuckConfig(FakeBuckConfig.builder().build()).getHgCmd(),
        ImmutableMap.of("PATH", System.getenv("PATH")));
  }
}
