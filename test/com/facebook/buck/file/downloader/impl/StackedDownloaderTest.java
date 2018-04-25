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

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.easymock.EasyMock;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class StackedDownloaderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void shouldCreateADownloaderEvenWithAnEmptyStack() {
    Downloader downloader =
        StackedDownloader.createFromConfig(
            FakeBuckConfig.builder().build(), new ToolchainProviderBuilder().build());

    assertNotNull(downloader);

    List<Downloader> downloaders = unpackDownloaders(downloader);
    assertFalse(downloaders.isEmpty());
  }

  @Test
  public void shouldAddOnDiskAndroidReposIfPresentInSdk() throws IOException {
    Downloader downloader =
        StackedDownloader.createFromConfig(
            FakeBuckConfig.builder().build(), new ToolchainProviderBuilder().build());

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

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.of(sdkRoot))
            .build();
    downloader =
        StackedDownloader.createFromConfig(FakeBuckConfig.builder().build(), toolchainProvider);
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
    boolean isWindows = Platform.detect() == Platform.WINDOWS;
    Configuration configuration = isWindows ? Configuration.windows() : Configuration.unix();
    FileSystem vfs = Jimfs.newFileSystem(configuration);

    Path m2Root = vfs.getPath(jimfAbsolutePath("/home/user/.m2/repository"));
    Files.createDirectories(m2Root);

    // Set up a config so we expect to see both a local and a remote maven repo.
    Path projectRoot = vfs.getPath(jimfAbsolutePath("/opt/local/src"));
    Files.createDirectories(projectRoot);
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(projectRoot))
            .setSections(
                "[maven_repositories]",
                "local = " + m2Root,
                "central = https://repo1.maven.org/maven2")
            .build();

    Downloader downloader =
        StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());

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

  private static String jimfAbsolutePath(String path) {
    boolean isWindows = Platform.detect() == Platform.WINDOWS;
    if (isWindows) {
      path = "C:" + path;
    }
    return path;
  }

  @Test
  public void shouldFallBackToTheDeprecatedMechanismForCreatingMavenRepos() {
    // Set up a config so we expect to see both a local and a remote maven repo.
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[download]", "maven_repo = https://repo1.maven.org/maven2")
            .build();

    Downloader downloader =
        StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());
    assertThat(downloader, includes(RemoteMavenDownloader.class));
  }

  @Test
  public void shouldIterativelyCheckEachDownloaderToSeeIfItWillReturnAnyFiles()
      throws URISyntaxException, IOException {
    Downloader noMatch = EasyMock.createNiceMock("noMatch", Downloader.class);
    Downloader exceptional = EasyMock.createNiceMock("exceptional", Downloader.class);
    Downloader works = EasyMock.createNiceMock("works", Downloader.class);
    Downloader neverCalled = EasyMock.createStrictMock("neverCalled", Downloader.class);

    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    URI uri = new URI("http://example.com/cheese/peas");
    Path output = Paths.get("never used");

    EasyMock.expect(noMatch.fetch(eventBus, uri, output)).andReturn(false);
    EasyMock.expect(exceptional.fetch(eventBus, uri, output)).andThrow(new IOException(""));
    EasyMock.expect(works.fetch(eventBus, uri, output)).andReturn(true);
    // neverCalled is never called, and so needs no expectations.

    EasyMock.replay(noMatch, exceptional, works, neverCalled);

    StackedDownloader downloader =
        new StackedDownloader(ImmutableList.of(noMatch, exceptional, works, neverCalled));
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

  @Test
  public void shouldThrowReadableExceptionWhenUrlPathDoesntExist() {
    String pathNotExist = "file://not/a/valid/path";
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[download]", String.format("maven_repo = %s", pathNotExist))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        String.format(
            "Error occurred when attempting to use %s "
                + "as a local Maven repository as configured",
            pathNotExist));

    StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());
  }

  @Test
  public void shouldThrowReadableExceptionWhenPathDoesntExist() {
    String pathNotExist = "//not/a/valid/path";
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[download]", String.format("maven_repo = %s", pathNotExist))
            .build();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        String.format(
            "Error occurred when attempting to use %s "
                + "as a local Maven repository as configured",
            pathNotExist));

    StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());
  }

  @Test
  public void shouldUseRetryingDownloaderIfMaxNumberOfRetriesIsSet() {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[download]", "max_number_of_retries = 1").build();

    Downloader downloader =
        StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());
    assertThat(downloader, includes(RetryingDownloader.class));
  }

  @Test
  public void shouldNotUseRetryingDownloaderIfMaxNumberOfRetriesIsSet() {
    BuckConfig config = FakeBuckConfig.builder().build();
    Downloader downloader =
        StackedDownloader.createFromConfig(config, new ToolchainProviderBuilder().build());
    assertThat(downloader, not(includes(RetryingDownloader.class)));
  }

  private Matcher<Downloader> includes(Class<? extends Downloader> clazz) {
    return new BaseMatcher<Downloader>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("downloader must include ").appendValue(clazz);
      }

      @Override
      public boolean matches(Object object) {
        Downloader downloader = (Downloader) object;
        List<Downloader> downloaders = unpackDownloaders(downloader);
        boolean seenRetrying = false;

        for (Downloader seen : downloaders) {
          if (clazz.isInstance(seen)) {
            seenRetrying = true;
          }
        }
        return seenRetrying;
      }
    };
  }
}
