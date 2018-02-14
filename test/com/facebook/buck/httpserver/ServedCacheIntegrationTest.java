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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.BuckConfigTestUtils;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemDelegate;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ServedCacheIntegrationTest {
  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  private static final Path A_FILE_PATH = Paths.get("aFile");
  private static final String A_FILE_DATA = "somedata";
  public static final RuleKey A_FILE_RULE_KEY = new RuleKey("0123456789");

  private ProjectFilesystem projectFilesystem;
  private WebServer webServer = null;
  private BuckEventBus buckEventBus;
  private ArtifactCache dirCache;
  public static final ImmutableMap<String, String> A_FILE_METADATA =
      ImmutableMap.of("key", "value");

  private static final ListeningExecutorService DIRECT_EXECUTOR_SERVICE =
      MoreExecutors.newDirectExecutorService();

  @Before
  public void setUp() throws Exception {
    buckEventBus = BuckEventBusForTests.newInstance();
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot());
    projectFilesystem.writeContentsToPath(A_FILE_DATA, A_FILE_PATH);

    dirCache = createArtifactCache(createMockLocalDirCacheConfig());
    dirCache.store(
        ArtifactInfo.builder().addRuleKeys(A_FILE_RULE_KEY).setMetadata(A_FILE_METADATA).build(),
        BorrowablePath.notBorrowablePath(A_FILE_PATH));
  }

  @After
  public void closeWebServer() throws Exception {
    if (webServer != null) {
      webServer.stop();
    }
  }

  private ArtifactCacheBuckConfig createMockLocalConfig(String... configText) throws Exception {
    BuckConfig config =
        BuckConfigTestUtils.createFromReader(
            new StringReader(Joiner.on('\n').join(configText)),
            projectFilesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.of());
    return new ArtifactCacheBuckConfig(config);
  }

  private ArtifactCacheBuckConfig createMockLocalHttpCacheConfig(int port) throws Exception {
    return createMockLocalHttpCacheConfig(port, 1);
  }

  private ArtifactCacheBuckConfig createMockLocalHttpCacheConfig(int port, int retryCount)
      throws Exception {
    return createMockLocalConfig(
        "[cache]",
        "mode = http",
        String.format("http_url = http://127.0.0.1:%d/", port),
        String.format("http_max_fetch_retries = %d", retryCount));
  }

  private ArtifactCacheBuckConfig createMockLocalDirCacheConfig() throws Exception {
    return createMockLocalConfig(
        "[cache]", "mode = dir", "dir = test-cache", "http_timeout_seconds = 10000");
  }

  @Test
  public void testFetchFromServedDircache() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult =
        Futures.getUnchecked(
            serverBackedCache.fetchAsync(A_FILE_RULE_KEY, LazyPath.ofInstance(fetchedContents)));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(true));
    assertThat(cacheResult.getMetadata(), Matchers.equalTo(A_FILE_METADATA));
    assertThat(
        projectFilesystem.readFileIfItExists(fetchedContents).get(), Matchers.equalTo(A_FILE_DATA));
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
    ProjectFilesystem throwingStreamFilesystem =
        new DefaultProjectFilesystem(
            tmpDir.getRoot(), new DefaultProjectFilesystemDelegate(tmpDir.getRoot())) {
          private boolean throwingStreamServed = false;

          @Override
          public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
            InputStream inputStream = super.newFileInputStream(pathRelativeToProjectRoot);
            if (!throwingStreamServed
                && pathRelativeToProjectRoot.toString().contains("outgoing_rulekey")) {
              throwingStreamServed = true;
              return new ThrowAfterXBytesStream(inputStream, 10L);
            }
            return inputStream;
          }
        };

    webServer = new WebServer(/* port */ 0, throwingStreamFilesystem);
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));

    // Try again to make sure the exception didn't kill the server.
    cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.HIT));
  }

  @Test
  public void testExceptionDuringTheReadRetryingFail() throws Exception {
    ProjectFilesystem throwingStreamFilesystem =
        new DefaultProjectFilesystem(
            tmpDir.getRoot(), new DefaultProjectFilesystemDelegate(tmpDir.getRoot())) {
          private int throwingStreamServedCount = 0;

          @Override
          public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
            InputStream inputStream = super.newFileInputStream(pathRelativeToProjectRoot);
            if (throwingStreamServedCount < 3
                && pathRelativeToProjectRoot.toString().contains("outgoing_rulekey")) {
              throwingStreamServedCount++;
              return new ThrowAfterXBytesStream(inputStream, 10L);
            }
            return inputStream;
          }
        };

    webServer = new WebServer(/* port */ 0, throwingStreamFilesystem);
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get(), 3));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));
  }

  @Test
  public void testExceptionDuringTheReadRetryingSuccess() throws Exception {
    ProjectFilesystem throwingStreamFilesystem =
        new DefaultProjectFilesystem(
            tmpDir.getRoot(), new DefaultProjectFilesystemDelegate(tmpDir.getRoot())) {
          private int throwingStreamServedCount = 0;

          @Override
          public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
            InputStream inputStream = super.newFileInputStream(pathRelativeToProjectRoot);
            if (throwingStreamServedCount < 3
                && pathRelativeToProjectRoot.toString().contains("outgoing_rulekey")) {
              throwingStreamServedCount++;
              return new ThrowAfterXBytesStream(inputStream, 10L);
            }
            return inputStream;
          }
        };

    webServer = new WebServer(/* port */ 0, throwingStreamFilesystem);
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get(), 4));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.HIT));
  }

  @Test
  public void testMalformedDirCacheMetaData() throws Exception {
    ArtifactCache cache =
        TestArtifactCaches.createDirCacheForTest(
            projectFilesystem.getRootPath(), Paths.get("test-cache"));
    Path cacheFilePath =
        DirArtifactCacheTestUtil.getPathForRuleKey(
            cache, A_FILE_RULE_KEY, Optional.of(".metadata"));
    assertThat(projectFilesystem.exists(cacheFilePath), Matchers.is(true));
    try (DataOutputStream outputStream =
        new DataOutputStream(projectFilesystem.newFileOutputStream(cacheFilePath))) {
      outputStream.writeInt(1024);
    }

    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(Optional.of(dirCache));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.MISS));
  }

  @Test
  public void whenNoCacheIsServedLookupsAreErrors() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(Optional.empty());

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents));
    assertThat(cacheResult.getType(), Matchers.equalTo(CacheResultType.ERROR));
  }

  @Test
  public void canSetArtifactCacheWithoutRestartingServer() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(Optional.empty());

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    assertThat(
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents))
            .getType(),
        Matchers.equalTo(CacheResultType.ERROR));

    webServer.updateAndStartIfNeeded(Optional.of(dirCache));
    assertThat(
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents))
            .getType(),
        Matchers.equalTo(CacheResultType.HIT));

    webServer.updateAndStartIfNeeded(Optional.empty());
    assertThat(
        Futures.getUnchecked(serverBackedCache.fetchAsync(A_FILE_RULE_KEY, fetchedContents))
            .getType(),
        Matchers.equalTo(CacheResultType.ERROR));
  }

  @Test
  public void testStoreAndFetchNotBorrowable() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(
        ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readwrite"),
            projectFilesystem));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some key", "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));

    serverBackedCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
        BorrowablePath.notBorrowablePath(originalDataPath));

    cacheResult = Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(true));
    assertThat(cacheResult.getMetadata(), Matchers.equalTo(metadata));
    assertThat(
        projectFilesystem.readFileIfItExists(fetchedContents.get()).get(), Matchers.equalTo(data));
  }

  @Test
  public void testStoreAndFetchBorrowable() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(
        ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readwrite"),
            projectFilesystem));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some key", "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));

    serverBackedCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
        BorrowablePath.borrowablePath(originalDataPath));

    cacheResult = Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(true));
    assertThat(cacheResult.getMetadata(), Matchers.equalTo(metadata));
    assertThat(
        projectFilesystem.readFileIfItExists(fetchedContents.get()).get(), Matchers.equalTo(data));
  }

  @Test
  public void testStoreDisabled() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(
        ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readonly"),
            projectFilesystem));

    ArtifactCache serverBackedCache =
        createArtifactCache(createMockLocalHttpCacheConfig(webServer.getPort().get()));

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some key", "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    LazyPath fetchedContents = LazyPath.ofInstance(tmpDir.newFile());
    CacheResult cacheResult =
        Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));

    serverBackedCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
        BorrowablePath.notBorrowablePath(originalDataPath));

    cacheResult = Futures.getUnchecked(serverBackedCache.fetchAsync(ruleKey, fetchedContents));
    assertThat(cacheResult.getType().isSuccess(), Matchers.is(false));
  }

  @Test
  public void fullStackIntegrationTest() throws Exception {
    webServer = new WebServer(/* port */ 0, projectFilesystem);
    webServer.updateAndStartIfNeeded(
        ArtifactCaches.newServedCache(
            createMockLocalConfig(
                "[cache]",
                "dir = test-cache",
                "serve_local_cache = true",
                "served_local_cache_mode = readonly"),
            projectFilesystem));

    ArtifactCache serverBackedCache =
        createArtifactCache(
            createMockLocalConfig(
                "[cache]",
                "mode = dir,http",
                "two_level_cache_enabled=true",
                "two_level_cache_minimum_size=0b",
                "dir = server-backed-dir-cache",
                String.format("http_url = http://127.0.0.1:%d/", webServer.getPort().get())));

    ArtifactCache serverBackedDirCache =
        createArtifactCache(
            createMockLocalConfig("[cache]", "mode = dir", "dir = server-backed-dir-cache"));

    assertFalse(containsKey(serverBackedDirCache, A_FILE_RULE_KEY));
    assertTrue(containsKey(serverBackedCache, A_FILE_RULE_KEY));
    // The previous call should have propagated the key into the dir-cache.
    assertTrue(containsKey(serverBackedDirCache, A_FILE_RULE_KEY));

    RuleKey ruleKey = new RuleKey("00111222333444");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some key", "some value");
    Path originalDataPath = tmpDir.newFile();
    String data = "you won't believe this!";
    projectFilesystem.writeContentsToPath(data, originalDataPath);

    assertFalse(containsKey(serverBackedCache, ruleKey));

    serverBackedCache
        .store(
            ArtifactInfo.builder().addRuleKeys(ruleKey).setMetadata(metadata).build(),
            BorrowablePath.borrowablePath(originalDataPath))
        .get();

    assertTrue(containsKey(serverBackedCache, ruleKey));
    assertTrue(containsKey(serverBackedDirCache, ruleKey));
  }

  private boolean containsKey(ArtifactCache cache, RuleKey ruleKey) throws Exception {
    Path fetchedContents = tmpDir.newFile();
    CacheResult cacheResult =
        Futures.getUnchecked(cache.fetchAsync(ruleKey, LazyPath.ofInstance(fetchedContents)));
    assertThat(cacheResult.getType(), Matchers.oneOf(CacheResultType.HIT, CacheResultType.MISS));
    return cacheResult.getType().isSuccess();
  }

  private ArtifactCache createArtifactCache(ArtifactCacheBuckConfig buckConfig) {
    return new ArtifactCaches(
            buckConfig,
            buckEventBus,
            projectFilesystem,
            Optional.empty(),
            DIRECT_EXECUTOR_SERVICE,
            DIRECT_EXECUTOR_SERVICE,
            DIRECT_EXECUTOR_SERVICE,
            DIRECT_EXECUTOR_SERVICE)
        .newInstance();
  }
}
