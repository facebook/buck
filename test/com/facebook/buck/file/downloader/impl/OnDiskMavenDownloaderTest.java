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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.file.downloader.Downloader;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class OnDiskMavenDownloaderTest {

  private FileSystem filesystem;
  private Path root;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setupFilesystem() throws IOException {
    filesystem = Jimfs.newFileSystem(Configuration.unix());
    root = filesystem.getPath("/home/bob/.m2");
    Files.createDirectories(root);
  }

  @Test
  public void shouldOnlyAcceptMvnUris() throws IOException {
    Path relativePath = filesystem.getPath("some/file.txt");
    Path output = filesystem.getPath("output.txt");

    Downloader downloader = new OnDiskMavenDownloader(root);
    boolean result =
        downloader.fetch(BuckEventBusForTests.newInstance(), relativePath.toUri(), output);

    assertFalse(result);
  }

  @Test
  public void shouldDownloadFileFromLocalMavenRepo() throws URISyntaxException, IOException {
    URI uri = new URI("mvn:group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path source = root.resolve("group/project/0.1/project-0.1.jar");
    Files.createDirectories(source.getParent());
    Files.write(source, "cake".getBytes(UTF_8));

    Downloader downloader = new OnDiskMavenDownloader(root);
    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);

    String result = new String(Files.readAllBytes(output), UTF_8);

    assertThat("cake", Matchers.equalTo(result));
  }

  @Test
  public void shouldDownloadFileFromLocalMavenRepoWindows() throws URISyntaxException, IOException {
    FileSystem filesystem = Jimfs.newFileSystem(Configuration.windows());
    Path root = filesystem.getPath("C:\\Users\\bob\\.m2");
    Files.createDirectories(root);

    URI uri = new URI("mvn:group:project:jar:0.1");
    Path output = filesystem.getPath("output.txt");

    Path source = root.resolve("group/project/0.1/project-0.1.jar");
    Files.createDirectories(source.getParent());
    Files.write(source, "cake".getBytes(UTF_8));

    Downloader downloader = new OnDiskMavenDownloader(root);
    downloader.fetch(BuckEventBusForTests.newInstance(), uri, output);

    String result = new String(Files.readAllBytes(output), UTF_8);

    assertThat("cake", Matchers.equalTo(result));
  }

  @Test
  public void shouldThrowFileNotFoundExceptionWhenPathDoesntExist() throws FileNotFoundException {
    Path rootNotExist = filesystem.getPath("not/a/valid/path");

    thrown.expect(FileNotFoundException.class);
    thrown.expectMessage(String.format("Maven root %s doesn't exist", rootNotExist.toString()));

    new OnDiskMavenDownloader(rootNotExist);
  }
}
