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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class HgCmdLineInterfaceIntegrationTest {
  /**
   * Test constants
   */
  private static final long MASTER_THREE_TS = 1440589283L; // Wed Aug 26 11:41:23 2015 UTC
  private static final String MASTER_THREE_BOOKMARK = "master3";
  private static final String BRANCH_FROM_MASTER_THREE_BOOKMARK = "branch_from_master3";
  private static final String BRANCH_FROM_MASTER_TWO_ID = "2911b3";
  private static final String BRANCH_FROM_MASTER_THREE_ID = "dee670";
  private static final String MASTER_TWO_ID = "b1fd7e";
  private static final String MASTER_THREE_ID = "adf7a0";

  private static final String HG_REPOS_ZIP = "hg_repos.zip";
  private static final String REPOS_DIR = "repos";
  private static final String REPO_TWO_DIR = "hg_repo_two";
  private static final String REPO_THREE_DIR = "hg_repo_three";
  private static final String REPO_WITH_SUB_DIR = "hg_repo_with_subdir";

  /***
   *
   * Test data:
   *
   * The following repo is used in the tests:
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
   * There are two different variants (both stored inside HG_REPOS_ZIP):
   *
   * hg_repo_two: above, current tip @branch_from_master2, and no local changes.
   * hg_repo_three: above, current tip @branch_from_master3, and with local changes.
   *
   * Additionally hg_repo_with_subdir is a new hg_repo with a directory called subdir
   *
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
  public void setUp() {
    assumeHgInstalled();
  }

  @Test
  public void testCurrentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    String currentRevisionId = repoThreeCmdLine.currentRevisionId();
    assertThat(currentRevisionId.startsWith(BRANCH_FROM_MASTER_THREE_ID), is(true));
  }

  @Test
  public void testRevisionId() throws VersionControlCommandFailedException, InterruptedException {
    String currentRevisionId = repoThreeCmdLine.revisionId(MASTER_THREE_BOOKMARK);
    assertThat(currentRevisionId.startsWith(MASTER_THREE_ID), is(true));
  }

  @Test
  public void testRevisionIdOrAbsent() throws InterruptedException {
    Optional<String> masterRevision = repoThreeCmdLine.revisionIdOrAbsent(MASTER_THREE_BOOKMARK);
    Optional<String> absentRevision = repoThreeCmdLine.revisionIdOrAbsent("absent_bookmark");
    assertTrue(masterRevision.get().startsWith(MASTER_THREE_ID));
    assertEquals(absentRevision, Optional.<String>absent());
  }

  @Test
  public void whenWorkingDirectoryUnchangedThenHasWorkingDirectoryChangesReturnsFalse()
      throws VersionControlCommandFailedException, InterruptedException {
    assertThat(repoTwoCmdLine.changedFiles("."), hasSize(0));
  }

  @Test
  public void whenWorkingDirectoryChangedThenHasWorkingDirectoryChangesReturnsTrue()
      throws VersionControlCommandFailedException, InterruptedException {
    assertThat(repoThreeCmdLine.changedFiles("."), hasSize(Matchers.greaterThan(0)));
  }

  @Test
  public void testCommonAncestorWithBookmarks()
      throws VersionControlCommandFailedException, InterruptedException {
    String commonAncestor = repoThreeCmdLine.commonAncestor(
        BRANCH_FROM_MASTER_THREE_BOOKMARK,
        MASTER_THREE_BOOKMARK);

    assertThat(commonAncestor.startsWith(MASTER_THREE_ID), is(true));
  }

  @Test
  public void testExistingCommonAncestorOrAbsentWithBookmarks() throws InterruptedException {
    assertTrue(repoThreeCmdLine.commonAncestorOrAbsent(
        BRANCH_FROM_MASTER_THREE_BOOKMARK,
        MASTER_THREE_BOOKMARK)
        .get()
        .startsWith(MASTER_THREE_ID));
  }

  @Test
  public void testAbsentCommonAncestorOrAbsentWithBookmarks() throws InterruptedException {
    assertEquals(repoThreeCmdLine.commonAncestorOrAbsent(
        BRANCH_FROM_MASTER_THREE_BOOKMARK,
        "absent_bookmark"),
        Optional.<String>absent());
  }

  @Test
  public void testChangedFilesFromHead()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableSet<String> changedFiles = repoThreeCmdLine.changedFiles(".");
    assertThat(changedFiles, Matchers.contains("? local_change"));
  }

  @Test
  public void testChangedFilesFromCommonAncestor()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableSet<String> changedFiles = repoThreeCmdLine.changedFiles("ancestor(., master3)");
    assertThat(changedFiles,
        Matchers.containsInAnyOrder("A change3", "A change3-2", "? local_change"));
  }

  @Test
  public void whenBranchedFromLatestMasterThenCommonAncestorWithLatestMasterReturnsLatestMaster()
      throws VersionControlCommandFailedException, InterruptedException {
    String commonAncestor = repoThreeCmdLine.commonAncestor(
        BRANCH_FROM_MASTER_THREE_ID,
        MASTER_THREE_ID);

    assertThat(commonAncestor.startsWith(MASTER_THREE_ID), is(true));
  }

  @Test
  public void whenBranchedFromOldMasterThenCommonAncestorWithLatestMasterReturnsOldMaster()
      throws VersionControlCommandFailedException, InterruptedException {
    String commonAncestor = repoTwoCmdLine.commonAncestor(
        BRANCH_FROM_MASTER_TWO_ID,
        MASTER_THREE_ID);

    assertThat(commonAncestor.startsWith(MASTER_TWO_ID), is(true));
  }

  @Test
  public void testDiffBetweenRevisions()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals(
        "diff --git a/change2 b/change2new file mode 100644diff --git a/file3 b/file3deleted " +
            "file mode 100644",
        repoThreeCmdLine.diffBetweenRevisions("adf7a0", "2911b3"));
  }

  @Test
  public void testDiffBetweenTheSameRevision()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals("", repoThreeCmdLine.diffBetweenRevisions("adf7a0", "adf7a0"));
  }

  @Test
  public void testDiffBetweenDiffsOfDifferentBranches()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals(
        "diff --git a/change2 b/change2deleted file mode 100644diff --git a/change3 b/change3new " +
            "file mode 100644diff --git a/change3-2 b/change3-2new file mode 100644diff " +
            "--git a/file3 b/file3new file mode 100644",
        repoThreeCmdLine.diffBetweenRevisions("2911b3", "dee670"));
  }

  @Test
  public void testAllBookmarks()
      throws VersionControlCommandFailedException, InterruptedException {
    try {
      assertEquals(
          "{branch_from_master2=3:2911b3cab6b2, branch_from_master3=5:dee6702e3d5e, " +
              "master1=0:b870f77a2738, master2=1:b1fd7e5896af, master3=2:adf7a03ed6f1}",
          repoThreeCmdLine.allBookmarks().toString());
    } catch (VersionControlCommandFailedException e) {
      if (!e.getMessage().contains("option --all not recognized")) {
        throw new VersionControlCommandFailedException(e.getCause());
      }
    }
  }

  @Test
  public void testUntrackedFiles()
      throws VersionControlCommandFailedException, InterruptedException {
    assertEquals(ImmutableSet.of("? local_change"), repoThreeCmdLine.untrackedFiles());
  }

  @Test
  public void testCreateCmdLineInterfaceUsingHgSubDir()
      throws VersionControlCommandFailedException, InterruptedException {
    VersionControlCmdLineInterface subDirCmdLineInterface =
        makeCmdLine(reposPath.resolve(REPO_WITH_SUB_DIR));

    assertThat(subDirCmdLineInterface instanceof HgCmdLineInterface, is(true));
  }

  @Test
  public void testTimestamp() throws VersionControlCommandFailedException, InterruptedException {
    assertThat(
        MASTER_THREE_TS,
        is(equalTo(repoThreeCmdLine.timestampSeconds(MASTER_THREE_ID))));
  }

  @Test
  public void testTrackedBookmarksOffRevisionId() throws InterruptedException {
    ImmutableSet<String> bookmarks = ImmutableSet.of("master2");
    assertEquals(
        bookmarks,
        repoThreeCmdLine.trackedBookmarksOffRevisionId("b1fd7e", "2911b3", bookmarks));
    bookmarks = ImmutableSet.of("master3");
    assertEquals(
        bookmarks,
        repoThreeCmdLine.trackedBookmarksOffRevisionId("dee670", "adf7a0", bookmarks));
  }

  @Test
  public void givenNonVcDirThenFactoryReturnsNoOpCmdLine() throws InterruptedException {
    DefaultVersionControlCmdLineInterfaceFactory vcFactory =
        new DefaultVersionControlCmdLineInterfaceFactory(
            tempFolder.getRoot().toPath(),
            new TestProcessExecutorFactory(),
            new VersionControlBuckConfig(FakeBuckConfig.builder().build()),
            ImmutableMap.<String, String>of());
    VersionControlCmdLineInterface cmdLineInterface = vcFactory.createCmdLineInterface();
    assertEquals(NoOpCmdLineInterface.class, cmdLineInterface.getClass());
  }

  private static void assumeHgInstalled() {
    // If Mercurial is not installed on the build box, then skip tests.
    Assume.assumeTrue(repoTwoCmdLine instanceof HgCmdLineInterface);
  }

  private static Path explodeReposZip() throws IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(HgCmdLineInterfaceIntegrationTest.class);
    Path hgRepoZipPath = testDataDir.resolve(HG_REPOS_ZIP);

    Path tempFolderPath = tempFolder.getRoot().toPath();
    Path hgRepoZipCopyPath = tempFolderPath.resolve(HG_REPOS_ZIP);

    Files.copy(hgRepoZipPath, hgRepoZipCopyPath, REPLACE_EXISTING);

    Path reposPath = tempFolderPath.resolve(REPOS_DIR);
    Unzip.extractZipFile(
        hgRepoZipCopyPath,
        reposPath,
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    return reposPath;
  }

  private static VersionControlCmdLineInterface makeCmdLine(Path repoRootDir)
      throws InterruptedException {
    DefaultVersionControlCmdLineInterfaceFactory vcFactory =
        new DefaultVersionControlCmdLineInterfaceFactory(
            repoRootDir,
            new TestProcessExecutorFactory(),
            new VersionControlBuckConfig(FakeBuckConfig.builder().build()),
            ImmutableMap.<String, String>of());
    return vcFactory.createCmdLineInterface();
  }
}
