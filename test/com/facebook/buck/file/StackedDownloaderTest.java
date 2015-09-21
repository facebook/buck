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

package com.facebook.buck.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class StackedDownloaderTest {

  @Test
  public void shouldCreateADownloaderEvenWithAnEmptyStack() {
    Downloader downloader = StackedDownloader.createFromConfig(
        new FakeBuckConfig(),
        Optional.<Path>absent());

    assertNotNull(downloader);

    List<Downloader> downloaders = unpackDownloaders(downloader);
    assertFalse(downloaders.isEmpty());
  }

  @Test
  public void shouldAddOnDiskAndroidReposIfPresentInSdk() throws IOException {
    Downloader downloader = StackedDownloader.createFromConfig(
        new FakeBuckConfig(),
        Optional.<Path>absent());

    List<Downloader> downloaders = unpackDownloaders(downloader);
    for (Downloader seen : downloaders) {
      assertFalse(seen instanceof OnDiskMavenDownloader);
    }

    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path sdkRoot = vfs.getPath("/opt/android_sdk");
    Path androidM2 = sdkRoot.resolve("extras/android/m2repository");
    Path googleM2 = sdkRoot.resolve("extras/google/m2repository");
    Files.createDirectories(androidM2);
    Files.createDirectories(googleM2);

    downloader = StackedDownloader.createFromConfig(
        new FakeBuckConfig(),
        Optional.of(sdkRoot));
    downloaders = unpackDownloaders(downloader);

    int count = 0;
    for (Downloader seen : downloaders) {
      if (seen instanceof OnDiskMavenDownloader) {
        count++;
      }
    }
    assertEquals(2, count);
  }

  @Test
  public void createDownloadersForEachEntryInTheMavenRepositoriesSection() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    Path m2Root = vfs.getPath("/home/user/.m2/repository");
    Files.createDirectories(m2Root);

    // Set up a config so we expect to see both a local and a remote maven repo.
    Path projectRoot = vfs.getPath("/opt/local/src");
    Files.createDirectories(projectRoot);
    FakeBuckConfig config = new FakeBuckConfig(
        new ProjectFilesystem(projectRoot),
        "[maven_repositories]",
        "local = " + m2Root.toString(),
        "central = https://repo1.maven.org/maven2");

    Downloader downloader = StackedDownloader.createFromConfig(config, Optional.<Path>absent());

    List<Downloader> downloaders = unpackDownloaders(downloader);
    boolean seenRemote = false;
    boolean seenLocal = false;

    for (Downloader seen : downloaders) {
      if (seen instanceof RemoteMavenDownloader) {
        seenRemote = true;
      } else if (seen instanceof OnDiskMavenDownloader) {
        seenLocal = true;
      }
    }

    assertTrue(seenLocal);
    assertTrue(seenRemote);
  }

  @Test
  public void shouldFallBackToTheDeprecatedMechanismForCreatingMavenRepos() throws IOException {
    // Set up a config so we expect to see both a local and a remote maven repo.
    FakeBuckConfig config = new FakeBuckConfig(
        "[download]",
        "maven_repo = https://repo1.maven.org/maven2");

    Downloader downloader = StackedDownloader.createFromConfig(config, Optional.<Path>absent());

    List<Downloader> downloaders = unpackDownloaders(downloader);
    boolean seenRemote = false;

    for (Downloader seen : downloaders) {
      if (seen instanceof RemoteMavenDownloader) {
        seenRemote = true;
      }
    }

    assertTrue(seenRemote);
  }

  @Test
  public void shouldIterativelyCheckEachDownloaderToSeeIfItWillReturnAnyFiles()
      throws URISyntaxException, IOException {
    Downloader noMatch = EasyMock.createNiceMock("noMatch", Downloader.class);
    Downloader exceptional = EasyMock.createNiceMock("exceptional", Downloader.class);
    Downloader works = EasyMock.createNiceMock("works", Downloader.class);
    Downloader neverCalled = EasyMock.createStrictMock("neverCalled", Downloader.class);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    URI uri = new URI("http://example.com/cheese/peas");
    Path output = Paths.get("never used");

    EasyMock.expect(noMatch.fetch(eventBus, uri, output)).andReturn(false);
    EasyMock.expect(exceptional.fetch(eventBus, uri, output)).andThrow(new IOException(""));
    EasyMock.expect(works.fetch(eventBus, uri, output)).andReturn(true);
    // neverCalled is never called, and so needs no expectations.

    EasyMock.replay(noMatch, exceptional, works, neverCalled);

    StackedDownloader downloader = new StackedDownloader(
        ImmutableList.of(
            noMatch,
            exceptional,
            works,
            neverCalled));
    boolean result = downloader.fetch(eventBus, uri, output);

    assertTrue(result);

    EasyMock.verify(noMatch, exceptional, works, neverCalled);
  }

  @SuppressWarnings("unchecked")
  private List<Downloader> unpackDownloaders(Downloader downloader) {
    try {
      Field field = downloader.getClass().getDeclaredField("delegates");
      field.setAccessible(true);
      return (List<Downloader>) field.get(downloader);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
