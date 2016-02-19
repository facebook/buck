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
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

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

  private static final ListeningExecutorService DIRECT_EXECUTOR_SERVICE =
      MoreExecutors.newDirectExecutorService();

  @Before
  public void setUp() throws Exception {
    buckEventBus = BuckEventBusFactory.newInstance();
    projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    projectFilesystem.writeContentsToPath(A_FILE_DATA, A_FILE_PATH);

    dirCache = ArtifactCaches.newInstance(
        createMockLocalDirCacheConfig(),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);
    dirCache.store(
        ImmutableSet.of(A_FILE_RULE_KEY),
        A_FILE_METADATA,
        BorrowablePath.withPath(A_FILE_PATH));
  }

  @After
  public void closeWebServer() throws Exception {
    if (webServer != null) {
      webServer.stop();
    }
  }

  private ArtifactCacheBuckConfig createMockLocalConfig(
      String... configText) throws Exception {
    BuckConfig config = BuckConfigTestUtils.createFromReader(
        new StringReader(Joiner.on('\n').join(configText)),
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.<String, String>of());
    return new ArtifactCacheBuckConfig(config);
  }

  private ArtifactCacheBuckConfig createMockLocalHttpCacheConfig(int port) throws Exception {
    return createMockLocalConfig(
        "[cache]",
        "mode = http",
        String.format("http_url = http://127.0.0.1:%d/", port));
  }

  private ArtifactCacheBuckConfig createMockLocalDirCacheConfig() throws Exception {
    return createMockLocalConfig(
        "[cache]",
        "mode = dir",
        "dir = test-cache");
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
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult = serverBackedCache.fetch(
        A_FILE_RULE_KEY,
        LazyPath.ofInstance(fetchedContents));
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
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));

    // Try again to make sure the exception didn't kill the server.
    cacheResult = serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents);
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.HIT));
  }

  @Test
  public void testMalformedDirCacheMetaData() throws Exception {
    ArtifactCache cache = TestArtifactCaches.createDirCacheForTest(
        projectFilesystem.getRootPath(),
        Paths.get("test-cache"));
    Path cacheFilePath = DirArtifactCacheTestUtil.getPathForRuleKey(
        cache, A_FILE_RULE_KEY, Optional.of(".metadata"));
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
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
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
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
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
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
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

  @Test
  public void testMultipleNamedCaches() throws Exception {
    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    final RuleKey bFileRuleKey = new RuleKey("baadbeef");

    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache secondCache = new ArtifactCache() {
      @Override
      public CacheResult fetch(RuleKey ruleKey, LazyPath output) throws InterruptedException {
        if (ruleKey.equals(bFileRuleKey)) {
          try {
            projectFilesystem.writeContentsToPath("second", output.get());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          return CacheResult.hit("secondCache");
        }
        return CacheResult.miss();
      }

      @Override
      public ListenableFuture<Void> store(
          ImmutableSet<RuleKey> ruleKeys,
          ImmutableMap<String, String> metadata,
          BorrowablePath output) {
        return Futures.immediateFuture(null);
      }

      @Override
      public boolean isStoreSupported() {
        return true;
      }

      @Override
      public void close() {
        // Intentional no-op.
      }
    };
    assertThat(
        secondCache.fetch(A_FILE_RULE_KEY, fetchedContents).getType(),
        Matchers.equalTo(CacheResultType.MISS));
    assertThat(
        secondCache.fetch(bFileRuleKey, fetchedContents).getType(),
        Matchers.equalTo(CacheResultType.HIT));
    WebServer secondWebServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());

    try {
      secondWebServer.updateAndStartIfNeeded(Optional.of(secondCache));

      ArtifactCacheBuckConfig mutltiCacheConfig = createMockLocalConfig(
          "[cache]",
          "mode = http",
          "http_cache_names = one, two",
          "[cache#two]",
          String.format("http_url = http://127.0.0.1:%d/", secondWebServer.getPort().get()),
          "[cache#one]",
          String.format("http_url = http://127.0.0.1:%d/", webServer.getPort().get()));

      ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
          mutltiCacheConfig,
          buckEventBus,
          projectFilesystem,
          Optional.<String>absent(),
          DIRECT_EXECUTOR_SERVICE);

      assertThat(
          serverBackedCache.fetch(A_FILE_RULE_KEY, fetchedContents).getType(),
          Matchers.equalTo(CacheResultType.HIT));
      assertThat(
          serverBackedCache.fetch(bFileRuleKey, fetchedContents).getType(),
          Matchers.equalTo(CacheResultType.HIT));
    } finally {
      secondWebServer.stop();
    }
  }

  @Test
  public void testStoreAndFetch() throws Exception {
    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readwrite"),
            projectFilesystem));

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of(
        "some key",
        "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult = serverBackedCache.fetch(ruleKey, fetchedContents);
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));

    serverBackedCache.store(
        ImmutableSet.of(ruleKey),
        metadata,
        BorrowablePath.withPath(originalDataPath));

    cacheResult = serverBackedCache.fetch(ruleKey, fetchedContents);
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(true));
    assertThat(cacheResult.getMetadata(), Matchers.equalTo(metadata));
    assertThat(
        projectFilesystem.readFileIfItExists(fetchedContents.get()).get(),
        Matchers.equalTo(data));
  }

  @Test
  public void testStoreDisabled() throws Exception {
    webServer = new WebServer(
        /* port */ 0,
        projectFilesystem,
        "/static/",
        new ObjectMapper());
    webServer.updateAndStartIfNeeded(ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readonly"),
            projectFilesystem));

    ArtifactCache serverBackedCache = ArtifactCaches.newInstance(
        createMockLocalHttpCacheConfig(webServer.getPort().get()),
        buckEventBus,
        projectFilesystem,
        Optional.<String>absent(),
        DIRECT_EXECUTOR_SERVICE);

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of(
        "some key",
        "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult = serverBackedCache.fetch(ruleKey, fetchedContents);
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));

    serverBackedCache.store(
        ImmutableSet.of(ruleKey),
        metadata,
        BorrowablePath.withPath(originalDataPath));

    cacheResult = serverBackedCache.fetch(ruleKey, fetchedContents);
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));
  }
}
