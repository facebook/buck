/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.file;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HttpFileIntegrationTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  private HttpdForTests.CapturingHttpHandler httpdHandler;
  private HttpdForTests httpd;
  private static final String echoDotSh = "#!/bin/sh\necho \"Hello, world\"";
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "fetch_files", temporaryDir);
    httpdHandler =
        new HttpdForTests.CapturingHttpHandler(
            ImmutableMap.<String, byte[]>builder()
                .put("/foo/bar/echo.sh", echoDotSh.getBytes(Charsets.UTF_8))
                .put(
                    "/package/artifact_name/version/artifact_name-version-classifier.zip",
                    echoDotSh.getBytes(Charsets.UTF_8))
                .build());
    httpd = new HttpdForTests();
    httpd.addHandler(httpdHandler);
    httpd.start();
  }

  @After
  public void tearDown() throws Exception {
    httpd.close();
  }

  private void rewriteBuckFileTemplate() throws IOException {
    // Replace a few tokens with the real host and port where our server is running
    URI uri = httpd.getRootUri();
    workspace.writeContentsToPath(
        workspace
            .getFileContents("BUCK")
            .replace("<HOST>", uri.getHost())
            .replace("<PORT>", Integer.toString(uri.getPort())),
        "BUCK");
  }

  @Test
  public void setsExecutableBitToTrue() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("echo_executable.sh")
            .resolve("echo_executable.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_executable.sh");

    workspace.runBuckCommand("fetch", "//:echo_executable.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.isExecutable(workspace.resolve(outputPath)));
    Assert.assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    Assert.assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void setsExecutableBitToFalse() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("echo_nonexecutable.sh")
            .resolve("echo_nonexecutable.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_nonexecutable.sh");

    workspace.runBuckCommand("fetch", "//:echo_nonexecutable.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertFalse(Files.isExecutable(workspace.resolve(outputPath)));
    Assert.assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    Assert.assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void doesNotWriteFileIfDownloadFails() throws IOException, InterruptedException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("echo_bad_urls.sh")
            .resolve("echo_bad_urls.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_bad_urls.sh");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:echo_bad_urls.sh");

    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        matchesPattern(
            Pattern.compile(".*Unable to download http://.*/invalid_path.*", Pattern.DOTALL)));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    Assert.assertEquals(
        ImmutableList.of("/invalid_path", "/missing"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void doesNotWriteFileIfShaVerificationFails() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("echo_bad_hash.sh")
            .resolve("echo_bad_hash.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_bad_hash.sh");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:echo_bad_hash.sh");

    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "/foo/bar/echo.sh (hashes do not match. Expected 534be6d331e8f1ab7892f19e8fe23db4907bdc54f517a8b22adc82e69b6b1093, saw 2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca)"));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    Assert.assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        1, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
    Assert.assertTrue(Files.exists(workspace.resolve(scratchPath).resolve("echo_bad_hash.sh")));
    Assert.assertEquals(
        echoDotSh, workspace.getFileContents(scratchPath.resolve("echo_bad_hash.sh")));
  }

  @Test
  public void downloadsFileAndValidatesIt() throws IOException, InterruptedException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path outputPath = workspace.getBuckPaths().getGenDir().resolve("echo.sh").resolve("echo.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo.sh");

    workspace.runBuckCommand("fetch", "//:echo.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    Assert.assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void writesFileToAlternateLocationIfOutProvided()
      throws IOException, InterruptedException {
    workspace.setUp();
    rewriteBuckFileTemplate();

    Path relativeOutputPath = Paths.get("echo_with_out.sh", "some_file.sh");
    Path outputPath = workspace.getBuckPaths().getGenDir().resolve(relativeOutputPath);
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_with_out.sh");

    workspace.runBuckCommand("fetch", "//:echo_with_out.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    Assert.assertEquals(ImmutableList.of("/foo/bar/echo.sh"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsFromMavenCoordinates() throws IOException, InterruptedException {
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("download", ImmutableMap.of("maven_repo", httpd.getRootUri().toString())));

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("echo_from_maven.sh")
            .resolve("echo_from_maven.sh");
    Path scratchPath = workspace.getBuckPaths().getScratchDir().resolve("echo_from_maven.sh");

    workspace.runBuckCommand("fetch", "//:echo_from_maven.sh").assertSuccess();

    Assert.assertTrue(Files.exists(workspace.resolve(outputPath)));
    Assert.assertEquals(echoDotSh, workspace.getFileContents(outputPath));
    Assert.assertEquals(
        ImmutableList.of("/package/artifact_name/version/artifact_name-version-classifier.zip"),
        httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchPath)).filter(Files::isRegularFile).count());
  }
}
