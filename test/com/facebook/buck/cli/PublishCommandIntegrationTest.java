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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.jvm.java.JavaPaths;
import com.facebook.buck.maven.aether.AetherUtil;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.HttpdForTests.DummyPutRequestsHandler;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ImmutableSortedSet.Builder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PublishCommandIntegrationTest {
  public static final String EXPECTED_PUT_URL_PATH_BASE = "/com/example/foo/1.0/foo-1.0";
  public static final String JAR = ".jar";
  public static final String POM = ".pom";
  public static final String SRC_JAR = JavaPaths.SRC_JAR;
  public static final String SHA1 = ".sha1";
  public static final String TARGET = "//:foo";

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private DummyPutRequestsHandler requestsHandler;
  private HttpdForTests httpd;

  @Before
  public void setUp() throws Exception {
    requestsHandler = new DummyPutRequestsHandler();
    httpd = new HttpdForTests();
    httpd.addHandler(requestsHandler);
    httpd.start();
  }

  @After
  public void tearDown() throws Exception {
    httpd.close();
  }

  @Test
  public void testDependenciesTriggerPomGeneration() throws IOException {
    ProcessResult result = runValidBuckPublish("publish_fatjar");
    result.assertSuccess();
    List<String> putRequestsPaths = requestsHandler.getPutRequestsPaths();
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + POM));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + POM + SHA1));
  }

  @Test
  public void testBasicCase() throws IOException {
    ProcessResult result = runValidBuckPublish("publish");
    result.assertSuccess();
  }

  private ProcessResult runValidBuckPublish(String workspaceName) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, workspaceName, tmp);
    workspace.setUp();

    ProcessResult result = runBuckPublish(workspace, PublishCommand.INCLUDE_SOURCE_LONG_ARG);
    result.assertSuccess();
    List<String> putRequestsPaths = requestsHandler.getPutRequestsPaths();
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + JAR));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + JAR + SHA1));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + SRC_JAR));
    assertThat(putRequestsPaths, hasItem(EXPECTED_PUT_URL_PATH_BASE + SRC_JAR + SHA1));
    return result;
  }

  @Test
  public void testRequireRepoUrl() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "publish", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("publish", "//:foo");
    result.assertExitCode("url is required", ExitCode.COMMANDLINE_ERROR);
    assertTrue(result.getStderr().contains(PublishCommand.REMOTE_REPO_LONG_ARG));
  }

  @Test
  public void testErrorOnMultiplePublishDest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "publish", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "publish", "//:foo", "--remote-repo=http://foo.bar", "--to-maven-central");
    result.assertExitCode("please specify only a single remote", ExitCode.COMMANDLINE_ERROR);
    assertTrue(result.getStderr().contains(PublishCommand.REMOTE_REPO_LONG_ARG));
    assertTrue(result.getStderr().contains(PublishCommand.TO_MAVEN_CENTRAL_LONG_ARG));
  }

  @Test
  public void testDryDun() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "publish", tmp);
    workspace.setUp();

    ProcessResult result =
        runBuckPublish(
            workspace, PublishCommand.INCLUDE_SOURCE_LONG_ARG, PublishCommand.DRY_RUN_LONG_ARG);
    result.assertSuccess();

    assertTrue(requestsHandler.getPutRequestsPaths().isEmpty());

    String stdOut = result.getStdout();
    assertTrue(stdOut, stdOut.contains("com.example:foo:jar:1.0"));
    assertTrue(
        stdOut, stdOut.contains("com.example:foo:jar:" + AetherUtil.CLASSIFIER_SOURCES + ":1.0"));
    assertTrue(stdOut, stdOut.contains(MorePaths.pathWithPlatformSeparators("/foo#maven.jar")));
    assertTrue(stdOut, stdOut.contains(JavaPaths.SRC_JAR));
    assertTrue(stdOut, stdOut.contains(getMockRepoUrl()));
  }

  @Test
  public void testScalaPublish() throws IOException {
    runValidBuckPublish("publish_scala");
  }

  @Test
  public void testScalaPublishToFS() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "publish_scala", tmp);
    workspace.setUp();

    Path publishPath = tmp.newFolder();

    ProcessResult result =
        workspace.runBuckCommand(
            FluentIterable.from(new String[] {"publish"})
                .append(PublishCommand.INCLUDE_SOURCE_LONG_ARG)
                .append(PublishCommand.REMOTE_REPO_SHORT_ARG, publishPath.toUri().toString())
                .append(TARGET)
                .toArray(String.class));

    result.assertSuccess();

    File publisherRoot = publishPath.toFile();

    File jarFile = new File(publisherRoot, EXPECTED_PUT_URL_PATH_BASE + JAR);
    ImmutableSortedSet<ZipEntry> jarContents = getZipFilesFiltered(jarFile);
    assertEquals(1, jarContents.size());
    assertEquals("foo/bar/ScalaFoo.class", jarContents.first().getName());

    File srcFile = new File(publisherRoot, EXPECTED_PUT_URL_PATH_BASE + SRC_JAR);
    ImmutableSortedSet<ZipEntry> srcJarContents = getZipFilesFiltered(srcFile);
    assertEquals(1, srcJarContents.size());
    assertEquals("ScalaFoo.scala", srcJarContents.first().getName());
  }

  private static ImmutableSortedSet<ZipEntry> getZipFilesFiltered(File zipFile) throws IOException {
    return getZipContents(zipFile).stream()
        .filter(zipEntry -> !zipEntry.isDirectory())
        .filter(zipEntry -> !zipEntry.getName().startsWith("META-INF"))
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.comparing(ZipEntry::getName)));
  }

  private static ImmutableSortedSet<ZipEntry> getZipContents(File zipFile) throws IOException {
    assertTrue(zipFile + " should exits", zipFile.isFile());
    ZipInputStream inputStream = new ZipInputStream(new FileInputStream(zipFile));
    Builder<ZipEntry> zipEntries =
        ImmutableSortedSet.orderedBy(Comparator.comparing(ZipEntry::getName));
    ZipEntry entry = inputStream.getNextEntry();
    while (entry != null) {
      zipEntries.add(entry);
      entry = inputStream.getNextEntry();
    }
    return zipEntries.build();
  }

  private ProcessResult runBuckPublish(ProjectWorkspace workspace, String... extraArgs)
      throws IOException {
    return workspace.runBuckCommand(
        FluentIterable.from(new String[] {"publish"})
            .append(extraArgs)
            .append(PublishCommand.REMOTE_REPO_SHORT_ARG, getMockRepoUrl())
            .append(TARGET)
            .toArray(String.class));
  }

  private String getMockRepoUrl() {
    return httpd.getRootUri().toString();
  }
}
