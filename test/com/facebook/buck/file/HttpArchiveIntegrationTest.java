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
  public void doesNotWriteFileIfDownloadFails() throws IOException, InterruptedException {
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
  public void doesNotWriteFileIfShaVerificationFails() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("bad_hash.tar").resolve("bad_hash.tar");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("bad_hash.tar#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("bad_hash.tar#archive_download")
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
  public void writesFileToAlternateLocationIfOutProvided()
      throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("out_specified").resolve("some_directory");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("out_specified#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("out_specified#archive-download")
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
  public void downloadsZipFileAndValidatesIt() throws IOException, InterruptedException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(not(WINDOWS)));

    rewriteBuckFileTemplate(workspace);

    Path outputPath = workspace.getBuckPaths().getGenDir().resolve("test.zip").resolve("test.zip");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test.zip#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test.zip#archive-download")
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
  public void downloadsZipFileAndValidatesItWithNoSymlinksOnWindows()
      throws IOException, InterruptedException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(WINDOWS));

    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test_no_symlinks.zip")
            .resolve("test_no_symlinks.zip");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test_no_symlinks.zip#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test_no_symlinks.zip#archive-download")
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
  public void downloadsTarFileAndValidatesIt() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath = workspace.getBuckPaths().getGenDir().resolve("test.tar").resolve("test.tar");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test.tar#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test.tar#archive-download")
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
  public void downloadsTarBz2FileAndValidatesIt() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("test.tar.bz2").resolve("test.tar.bz2");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test.tar.bz2#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test.tar.bz2#archive-download")
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
  public void downloadsTarGzFileAndValidatesIt() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("test.tar.gz").resolve("test.tar.gz");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test.tar.gz#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test.tar.gz#archive-download")
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
  public void downloadsTarXzFileAndValidatesIt() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("test.tar.xz").resolve("test.tar.xz");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("test.tar.xz#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("test.tar.xz#archive-download")
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
  public void stripsZipPrefixIfRequested() throws IOException, InterruptedException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(not(WINDOWS)));

    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("strip.zip").resolve("strip.zip");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip.zip#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip.zip#archive-download")
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
  public void stripsZipPrefixIfRequestedWithNoSymlinksOnWindows()
      throws IOException, InterruptedException {
    // TODO: Windows has some issues w/ symlinks in zip files. Once fixed, remove this distinction
    Assume.assumeThat(Platform.detect(), is(WINDOWS));

    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip_no_symlinks.zip")
            .resolve("strip_no_symlinks.zip");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip_no_symlinks.zip#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip_no_symlinks.zip#archive-download")
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
  public void stripsTarPrefixIfRequested() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("strip.tar").resolve("strip.tar");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip.tar#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip.tar#archive-download")
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
  public void stripsTarBz2PrefixIfRequested() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("strip.tar.bz2").resolve("strip.tar.bz2");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip.tar.bz2#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip.tar.bz2#archive-download")
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
  public void stripsTarGzPrefixIfRequested() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("strip.tar.gz").resolve("strip.tar.gz");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip.tar.gz#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip.tar.gz#archive-download")
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
  public void stripsTarXzPrefixIfRequested() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("strip.tar.xz").resolve("strip.tar.xz");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("strip.tar.xz#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("strip.tar.xz#archive-download")
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
  public void typeOverridesUrl() throws IOException, InterruptedException {
    rewriteBuckFileTemplate(workspace);

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("override_type").resolve("override_type");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("override_type#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("override_type#archive-download")
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
  public void downloadZipFromMavenCoordinates() throws IOException, InterruptedException {
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("download", ImmutableMap.of("maven_repo", httpd.getRootUri().toString())));

    Path outputPath =
        workspace.getBuckPaths().getGenDir().resolve("zip_from_maven").resolve("zip_from_maven");
    Path scratchDownloadPath =
        workspace.getBuckPaths().getScratchDir().resolve("zip_from_maven#archive-download");
    Path downloadPath =
        workspace
            .getBuckPaths()
            .getGenDir()
            .resolve("zip_from_maven#archive-download")
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
}
