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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DownloadStepTest {

  class ConditionallyExplodingDownloader implements Downloader {

    private final Function<URI, Optional<byte[]>> fetcher;

    ConditionallyExplodingDownloader() {
      this.fetcher = uri -> Optional.empty();
    }

    ConditionallyExplodingDownloader(Function<URI, Optional<byte[]>> fetcher) {
      this.fetcher = fetcher;
    }

    @Override
    public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
      Optional<byte[]> fetchResults = fetcher.apply(uri);
      if (!fetchResults.isPresent()) {
        return false;
      } else {
        Files.createDirectories(output.getParent());
        Files.write(output, fetchResults.get());
        return true;
      }
    }
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  ProjectFilesystem filesystem;
  BuckEventBusForTests.CapturingConsoleEventListener listener;
  ExecutionContext context;
  Path outputPath = Paths.get("some", "dir", "out.txt");

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    listener = new BuckEventBusForTests.CapturingConsoleEventListener();
    context = TestExecutionContext.newInstance();
    context.getBuckEventBus().register(listener);
  }

  @Test
  public void returnsAFailureIfASingleUriIsPassedAndFails()
      throws IOException, InterruptedException {
    DownloadStep step =
        new DownloadStep(
            filesystem,
            new ConditionallyExplodingDownloader(),
            URI.create("https://example.com"),
            ImmutableList.of(),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    Assert.assertThat(
        listener.getLogMessages().get(0),
        Matchers.containsString("Unable to download: https://example.com"));
    Assert.assertEquals(-1, result.getExitCode());
  }

  @Test
  public void returnsAFailureIfAllUrisFail() throws IOException, InterruptedException {
    DownloadStep step =
        new DownloadStep(
            filesystem,
            new ConditionallyExplodingDownloader(),
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    ImmutableList<String> expectedErrors =
        ImmutableList.of(
            "Unable to download https://example.com/, trying to download from https://mirror1.example.com/ instead",
            "Unable to download https://mirror1.example.com/ (canonical URI: https://example.com/), trying to download from https://mirror2.example.com/ instead",
            "Unable to download https://mirror2.example.com/ (canonical URI: https://example.com/), trying to download from https://mirror3.example.com/ instead",
            "Unable to download https://mirror3.example.com/ (canonical URI: https://example.com/)");

    Assert.assertThat(
        listener.getLogMessages().get(0), Matchers.containsString(expectedErrors.get(0)));
    Assert.assertThat(
        listener.getLogMessages().get(1), Matchers.containsString(expectedErrors.get(1)));
    Assert.assertThat(
        listener.getLogMessages().get(2), Matchers.containsString(expectedErrors.get(2)));
    Assert.assertThat(
        listener.getLogMessages().get(3), Matchers.containsString(expectedErrors.get(3)));
    Assert.assertEquals(-1, result.getExitCode());
  }

  @Test
  public void succeedsIfOneDownloadSucceedsAndSha1IsCorrect()
      throws IOException, InterruptedException {
    ConditionallyExplodingDownloader downloader =
        new ConditionallyExplodingDownloader(
            uri -> {
              if (uri.getHost().equalsIgnoreCase("mirror2.example.com")) {
                return Optional.of("test".getBytes(Charsets.UTF_8));
              } else {
                return Optional.empty();
              }
            });
    DownloadStep step =
        new DownloadStep(
            filesystem,
            downloader,
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    ImmutableList<String> expectedErrors =
        ImmutableList.of(
            "Unable to download https://example.com/, trying to download from https://mirror1.example.com/ instead",
            "Unable to download https://mirror1.example.com/ (canonical URI: https://example.com/), trying to download from https://mirror2.example.com/ instead");

    Assert.assertThat(
        listener.getLogMessages().get(0), Matchers.containsString(expectedErrors.get(0)));
    Assert.assertThat(
        listener.getLogMessages().get(1), Matchers.containsString(expectedErrors.get(1)));
    Assert.assertEquals(StepExecutionResults.SUCCESS.getExitCode(), result.getExitCode());
    Assert.assertEquals("test", filesystem.readFileIfItExists(outputPath).get());
  }

  @Test
  public void succeedsIfOneDownloadSucceedsAndSha256IsCorrect()
      throws IOException, InterruptedException {
    ConditionallyExplodingDownloader downloader =
        new ConditionallyExplodingDownloader(
            uri -> {
              if (uri.getHost().equalsIgnoreCase("mirror2.example.com")) {
                return Optional.of("test".getBytes(Charsets.UTF_8));
              } else {
                return Optional.empty();
              }
            });
    DownloadStep step =
        new DownloadStep(
            filesystem,
            downloader,
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha256(
                HashCode.fromString(
                    "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    ImmutableList<String> expectedErrors =
        ImmutableList.of(
            "Unable to download https://example.com/, trying to download from https://mirror1.example.com/ instead",
            "Unable to download https://mirror1.example.com/ (canonical URI: https://example.com/), trying to download from https://mirror2.example.com/ instead");

    Assert.assertThat(
        listener.getLogMessages().get(0), Matchers.containsString(expectedErrors.get(0)));
    Assert.assertThat(
        listener.getLogMessages().get(1), Matchers.containsString(expectedErrors.get(1)));
    Assert.assertEquals(StepExecutionResults.SUCCESS.getExitCode(), result.getExitCode());
    Assert.assertEquals("test", filesystem.readFileIfItExists(outputPath).get());
  }

  @Test
  public void failsIfDownloadSucceedsAndSha1Fails() throws IOException, InterruptedException {
    ConditionallyExplodingDownloader downloader =
        new ConditionallyExplodingDownloader(
            uri -> Optional.of("test_bad_hash".getBytes(Charsets.UTF_8)));

    DownloadStep step =
        new DownloadStep(
            filesystem,
            downloader,
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    String expectedError =
        "Unable to download https://example.com/ (hashes do not match. Expected a94a8fe5ccb19ba61c4c0873d391e987982fbbd3, saw 2fbb3ff08bfc730eb74aab66df6af048517276bd)";

    Assert.assertThat(listener.getLogMessages().get(0), Matchers.containsString(expectedError));
    Assert.assertEquals(-1, result.getExitCode());
  }

  @Test
  public void failsIfDownloadSucceedsAndSha256Fails() throws IOException, InterruptedException {
    ConditionallyExplodingDownloader downloader =
        new ConditionallyExplodingDownloader(
            uri -> Optional.of("test_bad_hash".getBytes(Charsets.UTF_8)));

    DownloadStep step =
        new DownloadStep(
            filesystem,
            downloader,
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha256(
                HashCode.fromString(
                    "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")),
            outputPath);

    StepExecutionResult result = step.execute(context);

    String expectedError =
        "Unable to download https://example.com/ (hashes do not match. Expected 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08, saw a18d4d9219cee377aa2c4a35428caf63d8462ea6d762904badd828bb5f8f04c4)";

    Assert.assertThat(listener.getLogMessages().get(0), Matchers.containsString(expectedError));
    Assert.assertEquals(-1, result.getExitCode());
  }

  @Test
  public void returnsCorrectShortName() {
    DownloadStep step =
        new DownloadStep(
            filesystem,
            new ConditionallyExplodingDownloader(),
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    Assert.assertEquals("curl", step.getShortName());
  }

  @Test
  public void descriptionUsesCanonicalUri() {
    ProjectFilesystem projectFilesystem =
        new FakeProjectFilesystem() {
          @Override
          public Path resolve(Path relativePath) {
            return Paths.get("/abs/path").resolve(relativePath);
          }
        };

    DownloadStep step =
        new DownloadStep(
            projectFilesystem,
            new ConditionallyExplodingDownloader(),
            URI.create("https://example.com/"),
            ImmutableList.of(
                URI.create("https://mirror1.example.com/"),
                URI.create("https://mirror2.example.com/"),
                URI.create("https://mirror3.example.com/")),
            FileHash.ofSha1(HashCode.fromString("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3")),
            outputPath);

    Assert.assertEquals(
        "curl https://example.com/ -o '/abs/path/some/dir/out.txt'", step.getDescription(context));
  }
}
