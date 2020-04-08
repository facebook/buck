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

package com.facebook.buck.util.versioncontrol;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.TestProcessExecutorFactory;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
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
  @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

  private static VersionControlCmdLineInterface repoTwoCmdLine;
  private static VersionControlCmdLineInterface repoThreeCmdLine;
  private static Path reposPath;

  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {

    reposPath = tempFolder.getRoot().toPath().resolve(REPOS_DIR);
    Path repoTwoPath = reposPath.resolve(REPO_TWO_DIR);
    Path repoThreePath = reposPath.resolve(REPO_THREE_DIR);
    Optional<Path> hgCommand =
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get("hg"), EnvVariablesProvider.getSystemEnv());

    assumeTrue("Must have hg present to run", hgCommand.isPresent());
    setupTestRepos(hgCommand.get(), repoTwoPath, repoThreePath);

    repoTwoCmdLine = makeCmdLine(repoTwoPath);
    repoThreeCmdLine = makeCmdLine(repoThreePath);
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
    assertFalse(repoThreeCmdLine.diffBetweenRevisions("adf7a0", "adf7a0").isPresent());
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
        repoThreeCmdLine.diffBetweenRevisions("b1fd7e", "2911b3").get().get()) {
      InputStreamReader diffFileReader = new InputStreamReader(diffFileStream, Charsets.UTF_8);
      String actualDiff = CharStreams.toString(diffFileReader);
      // The output message from FB hg is a bit more than that from open source hg, use contains
      // here.
      assertThat(String.join("\n", expectedValue), Matchers.containsString(actualDiff));
    }
  }

  private static void run(Path directory, String... args) throws IOException, InterruptedException {
    Process proc = new ProcessBuilder().command(args).directory(directory.toFile()).start();
    proc.waitFor();
    assertEquals(
        String.format(
            "Could not run %s, got result %s", Joiner.on(" ").join(args), proc.exitValue()),
        0,
        proc.exitValue());
  }

  private static void setupTestRepos(Path hgPath, Path repoTwoPath, Path repoThreePath)
      throws InterruptedException, IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(HgCmdLineInterfaceIntegrationTest.class);

    // All of these are generated by
    // test/com/facebook/buck/util/versioncontrol/testdata/create_from_zip.sh
    Path repoTwoBundle = testDataDir.resolve(REPO_TWO_DIR + ".tar.bz2");
    Path repoTwoBookmarks = testDataDir.resolve(REPO_TWO_DIR + ".bookmarks");
    Path repoThreeBundle = testDataDir.resolve(REPO_THREE_DIR + ".tar.bz2");
    Path repoThreeBookmarks = testDataDir.resolve(REPO_TWO_DIR + ".bookmarks");
    String hg = hgPath.toString();

    assertTrue(repoTwoPath.toFile().mkdirs());
    assertTrue(repoThreePath.toFile().mkdirs());

    run(repoTwoPath, hg, "init");
    run(repoTwoPath, hg, "unbundle", repoTwoBundle.toString());
    addTestBookmarks(hg, repoTwoBookmarks, repoTwoPath);
    run(repoTwoPath, hg, "update", "branch_from_master2");

    run(repoThreePath, hg, "init");
    run(repoThreePath, hg, "unbundle", repoThreeBundle.toString());
    addTestBookmarks(hg, repoThreeBookmarks, repoThreePath);
    run(repoThreePath, hg, "update", "branch_from_master3");
    Files.write(new byte[] {}, repoThreePath.resolve("local_change").toFile());
    Files.write(new byte[] {}, repoThreePath.resolve("tracked_change").toFile());
    run(repoThreePath, hg, "add", "tracked_change");
  }

  private static void addTestBookmarks(String hg, Path bookmarksFile, Path hgRepoPath)
      throws IOException, InterruptedException {
    List<String> lines = Files.readLines(bookmarksFile.toFile(), Charsets.UTF_8);
    for (String line : lines) {
      String[] parts = line.split(" ");
      run(hgRepoPath, hg, "bookmark", "-r", parts[1], parts[0]);
    }
  }

  private static VersionControlCmdLineInterface makeCmdLine(Path repoRootDir) {
    return new DelegatingVersionControlCmdLineInterface(
        repoRootDir,
        new TestProcessExecutorFactory(),
        new VersionControlBuckConfig(FakeBuckConfig.builder().build()).getHgCmd(),
        ImmutableMap.of("PATH", EnvVariablesProvider.getSystemEnv().get("PATH")));
  }
}
