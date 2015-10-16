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

package com.facebook.buck.httpserver;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ServedCacheIntegrationTest {
  @Rule
  public TemporaryPaths tmpDir = new TemporaryPaths();

  private static final Path A_FILE_PATH = Paths.get("aFile");
  private static final String A_FILE_DATA = "somedata";
  public static final RuleKey A_FILE_RULE_KEY = new RuleKey("0123456789");

  private ProjectFilesystem projectFilesystem;
  private WebServer webServer = null;
  private BuckEventBus buckEventBus;
  private ArtifactCache dirCache;
  public static final ImmutableMap<String, String> A_FILE_METADATA = ImmutableMap.of(
      "key",
      "value");

  @Before
  public void setUp() throws Exception {
    buckEventBus = BuckEventBusFactory.newInstance();
    projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    projectFilesystem.writeContentsToPath(A_FILE_DATA, A_FILE_PATH);

    dirCache = ArtifactCaches.newInstance(
        createMockLocalDirCacheConfig(),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());
    dirCache.store(
        ImmutableSet.of(A_FILE_RULE_KEY),
        A_FILE_METADATA,
        A_FILE_PATH
    );
  }

  @After
  public void closeWebServer() throws Exception {
    if (webServer != null) {
      webServer.stop();
    }
  }

  private ArtifactCacheBuckConfig createMockLocalHttpCacheConfig(int port) throws Exception {
    BuckConfig config = BuckConfigTestUtils.createFromReader(
        new StringReader(
            Joiner.on('\n').join(
                "[cache]",
                "mode = http",
                String.format("http_url = http://127.0.0.1:%d/", port)
            )),
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.<String, String>of());
    return new ArtifactCacheBuckConfig(config);
  }

  private ArtifactCacheBuckConfig createMockLocalDirCacheConfig() throws Exception {
    BuckConfig config = BuckConfigTestUtils.createFromReader(
        new StringReader(
            Joiner.on('\n').join(
                "[cache]",
                "mode = dir",
                "dir = test-cache"
            )),
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.<String, String>of());
    return new ArtifactCacheBuckConfig(config);
  }

  @Test
  public void testFetchFromServedDircache() throws Exception {
    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(true));
    assertThat(cacheResult.getMetadata(), Matchers.equalTo(A_FILE_METADATA));
    assertThat(
        projectFilesystem.readFileIfItExists(fetchedContents).get(),
        Matchers.equalTo(A_FILE_DATA));
  }

  private static class ThrowAfterXBytesStream extends FilterInputStream {
    private final long bytesToThrowAfter;
    private long bytesRead = 0L;

    public ThrowAfterXBytesStream(InputStream in, long bytesToThrowAfter) {
      super(in);
      this.bytesToThrowAfter = bytesToThrowAfter;
    }

    @Override
    public int read() throws IOException {
      return recordRead(super.read());
    }

    @Override
    public int read(byte[] b) throws IOException {
      return recordRead(super.read(b));
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return recordRead(super.read(b, off, len));
    }

    private int recordRead(int read) throws IOException {
      bytesRead += read;
      if (bytesRead >= bytesToThrowAfter) {
        throw new IOException("Test exception while reading");
      }
      return read;
    }
  }

  @Test
  public void testExceptionDuringTheRead() throws Exception {
    ProjectFilesystem throwingStreamFilesystem = new ProjectFilesystem(tmpDir.getRoot()) {
      private boolean throwingStreamServed = false;
      @Override
      public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
        InputStream inputStream = super.newFileInputStream(pathRelativeToProjectRoot);
        if (!throwingStreamServed &&
            pathRelativeToProjectRoot.toString().contains("outgoing_rulekey")) {
          throwingStreamServed = true;
          return new ThrowAfterXBytesStream(inputStream, 10L);
        }
        return inputStream;
      }
    };

    webServer = new WebServer(
        /* port */ 0,
        throwingStreamFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));

    // Try again to make sure the exception didn't kill the server.
    cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.HIT));
  }

  @Test
  public void testMalformedDirCacheMetaData() throws Exception {
    Path cacheFilePath = tmpDir.getRoot().resolve("test-cache/" + A_FILE_RULE_KEY + ".metadata");
    assertThat(projectFilesystem.exists(cacheFilePath), Matchers.is(true));
    try (DataOutputStream outputStream =
             new DataOutputStream(projectFilesystem.newFileOutputStream(cacheFilePath))) {
      outputStream.writeInt(1024);
    }

    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.MISS));
  }

  @Test
  public void whenNoCacheIsServedLookupsAreErrors() throws Exception {
    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.<ArtifactCache>absent());

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));
  }

  @Test
  public void canSetArtifactCacheWithoutRestartingServer() throws Exception {
    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.<ArtifactCache>absent());

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent());

    Path fetchedContents = tmpDir.newFile();
    assertThat(
        serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents).getType(),
        Matchers.equalTo(CacheResultType.ERROR));

    webServer.updateAndStartIfNeeded(Optional.of(dirCache));
    assertThat(
        serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents).getType(),
        Matchers.equalTo(CacheResultType.HIT));

    webServer.updateAndStartIfNeeded(Optional.<ArtifactCache>absent());
    assertThat(
        serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents).getType(),
        Matchers.equalTo(CacheResultType.ERROR));
  }
}
