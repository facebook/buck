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

package com.facebook.buck.file.downloader.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.file.downloader.Downloader;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OnDiskGradleDownloaderTest {

  private FileSystem filesystem;
  private Path root;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setupFilesystem() throws IOException {
    filesystem = Jimfs.newFileSystem(Configuration.unix());
    root = filesystem.getPath("/home/bob/.gradle/caches/files");
    Files.createDirectories(root);
  }

  @Test
  public void shouldOnlyAcceptMvnUris() throws IOException {
    Path relativePath = filesystem.getPath("some/file.txt");
    Path output = filesystem.getPath("output.txt");

    Downloader downloader = new OnDiskGradleDownloader(root);
    boolean result =
        downloader.fetch(BuckEventBusForTests.newInstance(), relativePath.toUri(), output);

    assertFalse(result);
  }

  @Test
  public void shouldDownloadFileFromLocalGradleCache() throws URISyntaxException, IOException {
    URI uri = new URI("mvn:com.group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path source = root.resolve("com.group/project/0.1/hash/project-0.1.jar");
    Files.createDirectories(source.getParent());
    Files.write(source, "cake".getBytes(UTF_8));

    Downloader downloader = new OnDiskGradleDownloader(root);
    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);

    String result = new String(Files.readAllBytes(output), UTF_8);

    assertThat("cake", Matchers.equalTo(result));
  }

  @Test
  public void shouldDownloadFileFromLocalGradleCacheWindows()
      throws URISyntaxException, IOException {
    FileSystem filesystem = Jimfs.newFileSystem(Configuration.windows());
    Path root = filesystem.getPath("C:\\Users\\bob\\.m2");
    Files.createDirectories(root);

    URI uri = new URI("mvn:com.group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path source = root.resolve("com.group/project/0.1/hash/project-0.1.jar");
    Files.createDirectories(source.getParent());
    Files.write(source, "cake".getBytes(UTF_8));

    Downloader downloader = new OnDiskGradleDownloader(root);
    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);

    String result = new String(Files.readAllBytes(output), UTF_8);

    assertThat("cake", Matchers.equalTo(result));
  }

  @Test
  public void shouldDownloadFileFromLocalGradleCacheWithMultipleEntries()
      throws URISyntaxException, IOException {
    URI uri = new URI("mvn:com.group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path prebuiltJar = root.resolve("com.group/project/0.1/hash1/project-0.1.jar");
    Path sourcesJar = root.resolve("com.group/project/0.1/hash2/project-0.1-sources.jar");
    Files.createDirectories(prebuiltJar.getParent());
    Files.createDirectories(sourcesJar.getParent());
    Files.write(prebuiltJar, "cake".getBytes(UTF_8));
    Files.write(sourcesJar, "pastry".getBytes(UTF_8));

    Downloader downloader = new OnDiskGradleDownloader(root);
    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);

    String result = new String(Files.readAllBytes(output), UTF_8);

    assertThat("cake", Matchers.equalTo(result));
  }

  @Test
  public void shouldNotDownloadFileFromLocalGradleCacheWithMultipleSameEntries()
      throws URISyntaxException, IOException {
    URI uri = new URI("mvn:com.group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path prebuiltJar = root.resolve("com.group/project/0.1/hash1/project-0.1.jar");
    Path sourcesJar = root.resolve("com.group/project/0.1/hash2/project-0.1.jar");
    Files.createDirectories(prebuiltJar.getParent());
    Files.createDirectories(sourcesJar.getParent());
    Files.write(prebuiltJar, "cake".getBytes(UTF_8));
    Files.write(sourcesJar, "pastry".getBytes(UTF_8));

    Downloader downloader = new OnDiskGradleDownloader(root);

    thrown.expect(IOException.class);
    thrown.expectMessage(
        "Unable to download /home/bob/.gradle/caches/files/com.group/project/0.1/project-0.1.jar");

    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);
  }

  @Test
  public void shouldThrowFileNotFoundExceptionWhenPathDoesntExist() throws FileNotFoundException {
    Path rootNotExist = filesystem.getPath("not/a/valid/path");

    thrown.expect(FileNotFoundException.class);
    thrown.expectMessage(String.format("Maven root %s doesn't exist", rootNotExist.toString()));

    new OnDiskGradleDownloader(rootNotExist);
  }

  @Test
  public void shouldThrowExceptionWhenGradleHomeIsNotSet() throws MalformedURLException {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();

    try {
      OnDiskGradleDownloader.getUrl("gradle:GRADLE_USER_HOME/caches/files", buckConfig);
      Assert.fail("should have thrown an exception");
    } catch (HumanReadableException e) {
      assertTrue(e.getHumanReadableErrorMessage().contains("GRADLE_USER_HOME"));
    }
  }

  @Test
  public void shouldDetectGradleHomeWhenGradleHomeIsSet() throws MalformedURLException {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of("GRADLE_USER_HOME", "/usr/dev"))
            .build();

    URL url = OnDiskGradleDownloader.getUrl("gradle:GRADLE_USER_HOME/caches/files", buckConfig);

    assertEquals("file:/usr/dev/caches/files", url.toString());
  }

  @Test
  public void shouldReturnUrlWhenNoGradleHome() throws MalformedURLException {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();

    URL url = OnDiskGradleDownloader.getUrl("gradle:/usr/eng/caches/files", buckConfig);

    assertEquals("file:/usr/eng/caches/files", url.toString());
  }
}
