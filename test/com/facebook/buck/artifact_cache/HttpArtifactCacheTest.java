/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.TargetConfigurationSerializerForTests;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.OkHttpResponseWrapper;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class HttpArtifactCacheTest {

  private static final String SERVER = "http://localhost";

  private static final BuckEventBus BUCK_EVENT_BUS =
      new DefaultBuckEventBus(new IncrementingFakeClock(), new BuildId());
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
  private static final ListeningExecutorService DIRECT_EXECUTOR_SERVICE =
      MoreExecutors.newDirectExecutorService();
  private static final String ERROR_TEXT_TEMPLATE =
      "{cache_name} encountered an error: {error_message}";
  private static final int ERROR_TEXT_LIMIT = 100;

  private NetworkCacheArgs.Builder argsBuilder;

  private ResponseBody createResponseBody(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      ByteSource source,
      String data)
      throws IOException {

    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out)) {
      byte[] rawMetadata =
          HttpArtifactCacheBinaryProtocol.createMetadataHeader(ruleKeys, metadata, source);
      dataOut.writeInt(rawMetadata.length);
      dataOut.write(rawMetadata);
      dataOut.write(data.getBytes(Charsets.UTF_8));
      return ResponseBody.create(OCTET_STREAM, out.toByteArray());
    }
  }

  /**
   * Helper for creating simple HttpService instances from lambda functions.
   *
   * <p>This interface exists to allow lambda syntax.
   *
   * <p>Usage: {@code withMakeRequest((path, request) -> ...)}.
   */
  private interface FakeHttpService {
    HttpResponse makeRequest(String path, Request.Builder request) throws IOException;
  }

  private static HttpService withMakeRequest(FakeHttpService body) {
    return new HttpService() {
      @Override
      public HttpResponse makeRequest(String path, Request.Builder request) throws IOException {
        return body.makeRequest(path, request);
      }

      @Override
      public void close() {}
    };
  }

  @Before
  public void setUp() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    this.argsBuilder =
        NetworkCacheArgs.builder()
            .setCacheName("http")
            .setCacheMode(ArtifactCacheMode.http)
            .setRepository("some_repository")
            .setScheduleType("some_schedule")
            .setFetchClient(withMakeRequest((a, b) -> null))
            .setStoreClient(withMakeRequest((a, b) -> null))
            .setCacheReadMode(CacheReadMode.READWRITE)
            .setTargetConfigurationSerializer(
                TargetConfigurationSerializerForTests.create(cellPathResolver))
            .setUnconfiguredBuildTargetFactory(
                target ->
                    new ParsingUnconfiguredBuildTargetFactory().create(cellPathResolver, target))
            .setProjectFilesystem(projectFilesystem)
            .setBuckEventBus(BUCK_EVENT_BUS)
            .setHttpWriteExecutorService(DIRECT_EXECUTOR_SERVICE)
            .setHttpFetchExecutorService(DIRECT_EXECUTOR_SERVICE)
            .setErrorTextTemplate(ERROR_TEXT_TEMPLATE)
            .setErrorTextLimit(ERROR_TEXT_LIMIT);
  }

  @Test
  public void testFetchNotFound() throws Exception {
    List<Response> responseList = new ArrayList<>();
    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Response response =
                  new Response.Builder()
                      .code(HttpURLConnection.HTTP_NOT_FOUND)
                      .body(
                          ResponseBody.create(
                              MediaType.parse("application/octet-stream"), "extraneous"))
                      .protocol(Protocol.HTTP_1_1)
                      .request(requestBuilder.url(SERVER + path).build())
                      .message("")
                      .build();
              responseList.add(response);
              return new OkHttpResponseWrapper(response);
            }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    CacheResult result =
        Futures.getUnchecked(
            cache.fetchAsync(
                null,
                new RuleKey("00000000000000000000000000000000"),
                LazyPath.ofInstance(Paths.get("output/file"))));
    assertEquals(result.getType(), CacheResultType.MISS);
    assertTrue("response wasn't fully read!", responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchOK() throws Exception {
    Path output = Paths.get("output/file");
    String data = "test";
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    List<Response> responseList = new ArrayList<>();
    argsBuilder.setProjectFilesystem(filesystem);
    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              Response response =
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                              data))
                      .message("")
                      .build();
              responseList.add(response);
              return new OkHttpResponseWrapper(response);
            }));

    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
    assertEquals(result.cacheError().orElse(""), CacheResultType.HIT, result.getType());
    assertEquals(Optional.of(data), filesystem.readFileIfItExists(output));
    assertEquals(result.artifactSizeBytes(), Optional.of(filesystem.getFileSize(output)));
    assertTrue("response wasn't fully read!", responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchUrl() {
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    String expectedUri = "/artifacts/key/00000000000000000000000000000000";

    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              assertEquals(expectedUri, request.url().encodedPath());
              return new OkHttpResponseWrapper(
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap(new byte[0]),
                              "data"))
                      .message("")
                      .build());
            }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Futures.getUnchecked(
        cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(Paths.get("output/file"))));
    cache.close();
  }

  @Test
  public void testFetchURLWithBuildTarget() {
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar#baz");
    String expectedUri =
        "/artifacts/key/00000000000000000000000000000000?target=%2F%2Ffoo%3Abar%23baz";

    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              assertEquals(expectedUri, path);
              HttpUrl url = request.url();
              assertEquals("/artifacts/key/00000000000000000000000000000000", url.encodedPath());
              assertEquals("//foo:bar#baz", url.queryParameter("target"));
              return new OkHttpResponseWrapper(
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap(new byte[0]),
                              "data"))
                      .message("")
                      .build());
            }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Futures.getUnchecked(
        cache.fetchAsync(target, ruleKey, LazyPath.ofInstance(Paths.get("output/file"))));
    cache.close();
  }

  @Test
  public void testFetchBadChecksum() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    List<Response> responseList = new ArrayList<>();
    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              Response response =
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap(new byte[0]),
                              "data"))
                      .message("")
                      .build();
              responseList.add(response);
              return new OkHttpResponseWrapper(response);
            }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Path output = Paths.get("output/file");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.empty(), filesystem.readFileIfItExists(output));
    assertTrue("response wasn't fully read!", responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchExtraPayload() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    List<Response> responseList = new ArrayList<>();
    argsBuilder.setFetchClient(
        withMakeRequest(
            (path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              Response response =
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap("more data than length".getBytes(Charsets.UTF_8)),
                              "small"))
                      .message("")
                      .build();
              responseList.add(response);
              return new OkHttpResponseWrapper(response);
            }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Path output = Paths.get("output/file");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.empty(), filesystem.readFileIfItExists(output));
    assertTrue("response wasn't fully read!", responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchIOException() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    argsBuilder.setFetchClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              throw new IOException();
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Path output = Paths.get("output/file");
    CacheResult result =
        Futures.getUnchecked(
            cache.fetchAsync(
                null,
                new RuleKey("00000000000000000000000000000000"),
                LazyPath.ofInstance(output)));
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.empty(), filesystem.readFileIfItExists(output));
    cache.close();
  }

  @Test
  public void testStore() throws Exception {
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath(data, output);
    AtomicBoolean hasCalled = new AtomicBoolean(false);
    argsBuilder.setProjectFilesystem(filesystem);
    argsBuilder.setStoreClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER).build();
              hasCalled.set(true);

              Buffer buf = new Buffer();
              request.body().writeTo(buf);
              byte[] actualData = buf.readByteArray();

              byte[] expectedData;
              try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                  DataOutputStream dataOut = new DataOutputStream(out)) {
                dataOut.write(
                    HttpArtifactCacheBinaryProtocol.createKeysHeader(ImmutableSet.of(ruleKey)));
                byte[] metadata =
                    HttpArtifactCacheBinaryProtocol.createMetadataHeader(
                        ImmutableSet.of(ruleKey),
                        ImmutableMap.of(),
                        ByteSource.wrap(data.getBytes(Charsets.UTF_8)));
                dataOut.writeInt(metadata.length);
                dataOut.write(metadata);
                dataOut.write(data.getBytes(Charsets.UTF_8));
                expectedData = out.toByteArray();
              }

              assertArrayEquals(expectedData, actualData);

              Response response =
                  new Response.Builder()
                      .body(createDummyBody())
                      .code(HttpURLConnection.HTTP_ACCEPTED)
                      .protocol(Protocol.HTTP_1_1)
                      .request(request)
                      .message("")
                      .build();
              return new OkHttpResponseWrapper(response);
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    cache.storeImpl(ArtifactInfo.builder().addRuleKeys(ruleKey).build(), output);
    assertTrue(hasCalled.get());
    cache.close();
  }

  @Test(expected = IOException.class)
  public void testStoreIOException() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath("data", output);
    argsBuilder.setStoreClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              throw new IOException();
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    cache.storeImpl(
        ArtifactInfo.builder().addRuleKeys(new RuleKey("00000000000000000000000000000000")).build(),
        output);
    cache.close();
  }

  @Test
  public void testStoreMultipleKeys() throws Exception {
    RuleKey ruleKey1 = new RuleKey("00000000000000000000000000000000");
    RuleKey ruleKey2 = new RuleKey("11111111111111111111111111111111");
    String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath(data, output);
    Set<RuleKey> stored = new HashSet<>();
    argsBuilder.setProjectFilesystem(filesystem);
    argsBuilder.setStoreClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER).build();
              Buffer buf = new Buffer();
              request.body().writeTo(buf);
              try (DataInputStream in =
                  new DataInputStream(new ByteArrayInputStream(buf.readByteArray()))) {
                int keys = in.readInt();
                for (int i = 0; i < keys; i++) {
                  stored.add(new RuleKey(in.readUTF()));
                }
              }
              Response response =
                  new Response.Builder()
                      .body(createDummyBody())
                      .code(HttpURLConnection.HTTP_ACCEPTED)
                      .protocol(Protocol.HTTP_1_1)
                      .request(request)
                      .message("")
                      .build();
              return new OkHttpResponseWrapper(response);
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    cache.storeImpl(ArtifactInfo.builder().addRuleKeys(ruleKey1, ruleKey2).build(), output);
    assertThat(stored, Matchers.containsInAnyOrder(ruleKey1, ruleKey2));
    cache.close();
  }

  @Test
  public void testMulitStore() throws Exception {
    RuleKey ruleKey1 = new RuleKey("00000000000000000000000000000000");
    RuleKey ruleKey2 = new RuleKey("11111111111111111111111111111111");
    String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath(data, output);
    Set<RuleKey> stored = new HashSet<>();
    argsBuilder.setProjectFilesystem(filesystem);
    argsBuilder.setStoreClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER).build();
              Buffer buf = new Buffer();
              request.body().writeTo(buf);
              try (DataInputStream in =
                  new DataInputStream(new ByteArrayInputStream(buf.readByteArray()))) {
                int keys = in.readInt();
                for (int i = 0; i < keys; i++) {
                  stored.add(new RuleKey(in.readUTF()));
                }
              }
              Response response =
                  new Response.Builder()
                      .body(createDummyBody())
                      .code(HttpURLConnection.HTTP_ACCEPTED)
                      .protocol(Protocol.HTTP_1_1)
                      .request(request)
                      .message("")
                      .build();
              return new OkHttpResponseWrapper(response);
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    cache
        .store(
            ImmutableList.of(
                new Pair<>(
                    ArtifactInfo.builder().addRuleKeys(ruleKey1).build(),
                    BorrowablePath.notBorrowablePath(output)),
                new Pair<>(
                    ArtifactInfo.builder().addRuleKeys(ruleKey2).build(),
                    BorrowablePath.notBorrowablePath(output))))
        .get();
    assertThat(stored, Matchers.containsInAnyOrder(ruleKey1, ruleKey2));
    cache.close();
  }

  @Test
  public void testFetchWrongKey() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    RuleKey otherRuleKey = new RuleKey("11111111111111111111111111111111");
    String data = "data";

    argsBuilder.setFetchClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              Response response =
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .body(
                          createResponseBody(
                              ImmutableSet.of(otherRuleKey),
                              ImmutableMap.of(),
                              ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                              data))
                      .message("")
                      .build();
              return new OkHttpResponseWrapper(response);
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Path output = Paths.get("output/file");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.empty(), filesystem.readFileIfItExists(output));
    cache.close();
  }

  @Test
  public void testFetchMetadata() {
    Path output = Paths.get("output/file");
    String data = "test";
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");
    argsBuilder.setFetchClient(
        withMakeRequest(
            ((path, requestBuilder) -> {
              Request request = requestBuilder.url(SERVER + path).build();
              Response response =
                  new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(HttpURLConnection.HTTP_OK)
                      .message("")
                      .body(
                          createResponseBody(
                              ImmutableSet.of(ruleKey),
                              metadata,
                              ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                              data))
                      .build();
              return new OkHttpResponseWrapper(response);
            })));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
    assertEquals(CacheResultType.HIT, result.getType());
    assertEquals(metadata, result.getMetadata());
    cache.close();
  }

  @Test
  public void errorTextReplaced() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    String cacheName = "http cache";
    RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    RuleKey otherRuleKey = new RuleKey("11111111111111111111111111111111");
    String data = "data";
    AtomicBoolean consoleEventReceived = new AtomicBoolean(false);
    argsBuilder
        .setCacheName(cacheName)
        .setProjectFilesystem(filesystem)
        .setBuckEventBus(
            new DefaultBuckEventBus(new IncrementingFakeClock(), new BuildId()) {
              @Override
              public void post(BuckEvent event) {
                if (event instanceof ConsoleEvent) {
                  consoleEventReceived.set(true);
                  ConsoleEvent consoleEvent = (ConsoleEvent) event;
                  assertThat(consoleEvent.getMessage(), Matchers.containsString(cacheName));
                  assertThat(
                      consoleEvent.getMessage(), Matchers.containsString("incorrect key name"));
                }
              }
            })
        .setFetchClient(
            withMakeRequest(
                (path, requestBuilder) -> {
                  Request request = requestBuilder.url(SERVER + path).build();
                  Response response =
                      new Response.Builder()
                          .request(request)
                          .protocol(Protocol.HTTP_1_1)
                          .code(HttpURLConnection.HTTP_OK)
                          .body(
                              createResponseBody(
                                  ImmutableSet.of(otherRuleKey),
                                  ImmutableMap.of(),
                                  ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                                  data))
                          .message("")
                          .build();
                  return new OkHttpResponseWrapper(response);
                }));
    HttpArtifactCache cache = new HttpArtifactCache(argsBuilder.build());
    Path output = Paths.get("output/file");

    for (int i = 0; i < ERROR_TEXT_LIMIT + 1; ++i) {
      CacheResult result =
          Futures.getUnchecked(cache.fetchAsync(null, ruleKey, LazyPath.ofInstance(output)));
      assertEquals(CacheResultType.ERROR, result.getType());
      assertEquals(Optional.empty(), filesystem.readFileIfItExists(output));
    }
    assertTrue(consoleEventReceived.get());

    cache.close();
  }

  private static ResponseBody createDummyBody() {
    return ResponseBody.create(MediaType.parse("text/plain"), "SUCCESS");
  }
}
