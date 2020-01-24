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

package com.facebook.buck.file;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
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
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HttpArchiveIntegrationTest {

  private static Path mainDotJavaPath = Paths.get("src", "com", "facebook", "buck", "Main.java");
  private static String mainDotJavaContents =
      "class Main { public static void main(String[] args) { return; } }\n";
  private static Path echoDotShPath = Paths.get("echo.sh");
  private static String echoDotShContents = "#!/bin/sh\necho 'testing'\n";

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  private HttpdForTests.CapturingHttpHandler httpdHandler;
  private HttpdForTests httpd;
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "archives", temporaryDir);
    workspace.setUp();
    httpdHandler =
        new HttpdForTests.CapturingHttpHandler(
            ImmutableMap.<String, byte[]>builder()
                .put("/foo.tar", Files.readAllBytes(workspace.resolve("output.tar")))
                .put(
                    "/really_a_zip.tar",
                    Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")))
                .put("/foo.tar.bz2", Files.readAllBytes(workspace.resolve("output.tar.bz2")))
                .put("/foo.tar.gz", Files.readAllBytes(workspace.resolve("output.tar.gz")))
                .put("/foo.tar.xz", Files.readAllBytes(workspace.resolve("output.tar.xz")))
                .put("/foo.zip", Files.readAllBytes(workspace.resolve("output.zip")))
                .put(
                    "/foo_no_symlinks.zip",
                    Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")))
                .put(
                    "/package/artifact_name/version/artifact_name-version-classifier.zip",
                    Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")))
                .build());
    httpd = new HttpdForTests();
    httpd.addHandler(httpdHandler);
    httpd.start();
  }

  @After
  public void tearDown() throws Exception {
    httpd.close();
  }

  private void rewriteBuckFileTemplate(ProjectWorkspace workspace) throws IOException {
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
  public void doesNotWriteFileIfDownloadFails() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("bad_urls.tar").resolve("bad_urls.tar");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("bad_urls.tar#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("bad_urls.tar#archive_download")
            .resolve("bad_urls.tar");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:bad_urls.tar");

    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        matchesPattern(
            Pattern.compile(".*Unable to download http://.*/invalid_path.*", Pattern.DOTALL)));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("bad_urls.tar")));
    Assert.assertFalse(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertEquals(
        ImmutableList.of("/invalid_path", "/missing"), httpdHandler.getRequestedPaths());
  }

  @Test
  public void doesNotWriteFileIfShaVerificationFails() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bad_hash.tar"), "%s")
            .resolve("bad_hash.tar");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:bad_hash.tar#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:bad_hash.tar#archive_download"), "%s")
            .resolve("bad_hash.tar");

    ProcessResult result = workspace.runBuckCommand("fetch", "//:bad_hash.tar");

    result.assertFailure();
    Assert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "/foo.tar (hashes do not match. Expected 534be6d331e8f1ab7892f19e8fe23db4907bdc54f517a8b22adc82e69b6b1093, saw 4828b132aef4234e1bd3f0d003379e067061ad9bc4aa5201e3c3629f94dc2dba)"));
    Assert.assertFalse(Files.exists(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(scratchDownloadPath).resolve("bad_hash.tar")));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar")),
        Files.readAllBytes(workspace.resolve(scratchDownloadPath).resolve("bad_hash.tar")));
    Assert.assertFalse(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertEquals(ImmutableList.of("/foo.tar"), httpdHandler.getRequestedPaths());
  }

  @Test
  public void writesFileToAlternateLocationIfOutProvided() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:out_specified"), "%s")
            .resolve("some_directory");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:out_specified#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:out_specified#archive-download"), "%s")
            .resolve("some_directory");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:out_specified").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("out_specified")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));

    Assert.assertEquals(ImmutableList.of("/foo.tar"), httpdHandler.getRequestedPaths());
  }

  @Test
  public void downloadsZipFileAndValidatesIt() throws IOException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(not(WINDOWS)));

    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.zip"), "%s")
            .resolve("test.zip");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:test.zip#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.zip#archive-download"), "%s")
            .resolve("test.zip");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test.zip").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("test.zip")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.zip"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsZipFileAndValidatesItWithNoSymlinksOnWindows() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//:test_no_symlinks.zip"),
                    "%s"))
            .resolve("test_no_symlinks.zip");
    Path scratchDownloadPath =
        workspace.resolve(
            BuildTargetPaths.getScratchPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//:test_no_symlinks.zip#archive-download"),
                "%s"));
    Path downloadPath =
        workspace
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance("//:test_no_symlinks.zip#archive-download"),
                    "%s"))
            .resolve("test_no_symlinks.zip");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test_no_symlinks.zip").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("test_no_symlinks.zip")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo_no_symlinks.zip"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsTarFileAndValidatesIt() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar"), "%s")
            .resolve("test.tar");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:test.tar#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar#archive-download"), "%s")
            .resolve("test.tar");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test.tar").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("test.tar")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsTarBz2FileAndValidatesIt() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.bz2"), "%s")
            .resolve("test.tar.bz2");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:test.tar.bz2#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.bz2#archive-download"), "%s")
            .resolve("test.tar.bz2");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test.tar.bz2").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("test.tar.bz2")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.bz2")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.bz2"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsTarGzFileAndValidatesIt() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.gz"), "%s")
            .resolve("test.tar.gz");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:test.tar.gz#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.gz#archive-download"), "%s")
            .resolve("test.tar.gz");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test.tar.gz").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("test.tar.gz")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.gz")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.gz"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadsTarXzFileAndValidatesIt() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.xz"), "%s")
            .resolve("test.tar.xz");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:test.tar.xz#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:test.tar.xz#archive-download"), "%s")
            .resolve("test.tar.xz");
    Path expectedMainDotJavaPath = outputPath.resolve("root").resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve("root").resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:test.tar.xz").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("test.tar.xz")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.xz")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.xz"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsZipPrefixIfRequested() throws IOException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(not(WINDOWS)));

    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.zip"), "%s")
            .resolve("strip.zip");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip.zip#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.zip#archive-download"), "%s")
            .resolve("strip.zip");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip.zip").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip.zip")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.zip"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsZipPrefixIfRequestedWithNoSymlinksOnWindows() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip_no_symlinks.zip"), "%s")
            .resolve("strip_no_symlinks.zip");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip_no_symlinks.zip#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(
                BuildTargetFactory.newInstance("//:strip_no_symlinks.zip#archive-download"), "%s")
            .resolve("strip_no_symlinks.zip");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip_no_symlinks.zip").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip_no_symlinks.zip")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo_no_symlinks.zip"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsTarPrefixIfRequested() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar"), "%s")
            .resolve("strip.tar");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip.tar#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar#archive-download"), "%s")
            .resolve("strip.tar");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip.tar").assertSuccess();

    Assert.assertFalse(Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip.tar")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsTarBz2PrefixIfRequested() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.bz2"), "%s")
            .resolve("strip.tar.bz2");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip.tar.bz2#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.bz2#archive-download"), "%s")
            .resolve("strip.tar.bz2");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip.tar.bz2").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip.tar.bz2")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.bz2")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.bz2"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsTarGzPrefixIfRequested() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.gz"), "%s")
            .resolve("strip.tar.gz");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip.tar.gz#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.gz#archive-download"), "%s")
            .resolve("strip.tar.gz");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip.tar.gz").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip.tar.gz")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.gz")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.gz"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void stripsTarXzPrefixIfRequested() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.xz"), "%s")
            .resolve("strip.tar.xz");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:strip.tar.xz#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:strip.tar.xz#archive-download"), "%s")
            .resolve("strip.tar.xz");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:strip.tar.xz").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("strip.tar.xz")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output.tar.xz")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/foo.tar.xz"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void typeOverridesUrl() throws IOException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:override_type"), "%s")
            .resolve("override_type");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:override_type#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:override_type#archive-download"), "%s")
            .resolve("override_type");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:override_type").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("override_type")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(ImmutableList.of("/really_a_zip.tar"), httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void downloadZipFromMavenCoordinates() throws IOException {
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("download", ImmutableMap.of("maven_repo", httpd.getRootUri().toString())));

    Path outputPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:zip_from_maven"), "%s")
            .resolve("zip_from_maven");
    Path scratchDownloadPath =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//:zip_from_maven#archive-download"), "%s");
    Path downloadPath =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:zip_from_maven#archive-download"), "%s")
            .resolve("zip_from_maven");
    Path expectedMainDotJavaPath = outputPath.resolve(mainDotJavaPath);
    Path expectedEchoDotShPath = outputPath.resolve(echoDotShPath);

    workspace.runBuckCommand("fetch", "//:zip_from_maven").assertSuccess();

    Assert.assertFalse(
        Files.exists(workspace.resolve(scratchDownloadPath).resolve("zip_from_maven")));
    Assert.assertTrue(Files.exists(workspace.resolve(downloadPath)));
    Assert.assertArrayEquals(
        Files.readAllBytes(workspace.resolve("output_no_symlinks.zip")),
        Files.readAllBytes(workspace.resolve(downloadPath)));
    Assert.assertTrue(Files.isDirectory(workspace.resolve(outputPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedMainDotJavaPath)));
    Assert.assertTrue(Files.exists(workspace.resolve(expectedEchoDotShPath)));
    Assert.assertEquals(mainDotJavaContents, workspace.getFileContents(expectedMainDotJavaPath));
    Assert.assertEquals(echoDotShContents, workspace.getFileContents(expectedEchoDotShPath));
    if (Platform.detect() != WINDOWS) {
      Assert.assertFalse(Files.isExecutable(workspace.resolve(expectedMainDotJavaPath)));
      Assert.assertTrue(Files.isExecutable(workspace.resolve(expectedEchoDotShPath)));
    }

    Assert.assertEquals(
        ImmutableList.of("/package/artifact_name/version/artifact_name-version-classifier.zip"),
        httpdHandler.getRequestedPaths());
    Assert.assertEquals(
        0, Files.walk(workspace.resolve(scratchDownloadPath)).filter(Files::isRegularFile).count());
  }

  @Test
  public void canBeUsedAsDependencyInRuleAnalysis() throws IOException {
    workspace.setUp();
    rewriteBuckFileTemplate(workspace);

    workspace.addBuckConfigLocalOption("parser", "default_build_file_syntax", "skylark");
    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "enabled");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");
    workspace.addBuckConfigLocalOption("download", "in_build", "true");

    workspace.runBuckBuild("//rag:expect_path").assertSuccess();
  }
}
