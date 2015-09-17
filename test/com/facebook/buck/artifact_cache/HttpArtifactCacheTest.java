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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.HttpArtifactCacheEvent;
import com.facebook.buck.event.HttpArtifactCacheEvent.Finished;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okio.Buffer;

public class HttpArtifactCacheTest {

  private static final BuckEventBus BUCK_EVENT_BUS =
      new BuckEventBus(new IncrementingFakeClock(), new BuildId());
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private ResponseBody createResponseBody(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      ByteSource source,
      String data)
      throws IOException {

    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         DataOutputStream dataOut = new DataOutputStream(out)) {
      byte[] rawMetadata =
          HttpArtifactCacheBinaryProtocol.createMetadataHeader(
              ruleKeys,
              metadata,
              source);
      dataOut.writeInt(rawMetadata.length);
      dataOut.write(rawMetadata);
      dataOut.write(data.getBytes(Charsets.UTF_8));
      return ResponseBody.create(OCTET_STREAM, out.toByteArray());
    }
  }

  private static Finished.Builder createFinishedEventBuilder() {
    HttpArtifactCacheEvent.Started started = HttpArtifactCacheEvent.newFetchStartedEvent();
    started.configure(-1, -1, -1, new BuildId());
    return HttpArtifactCacheEvent.newFinishedEventBuilder(started);
  }

  @Test
  public void testFetchNotFound() throws Exception {
    final List<Response> responseList = Lists.newArrayList();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            Response response =
                new Response.Builder()
                    .code(HttpURLConnection.HTTP_NOT_FOUND)
                    .body(
                        ResponseBody.create(
                            MediaType.parse("application/octet-stream"), "extraneous"))
                    .protocol(Protocol.HTTP_1_1)
                    .request(request)
                    .build();
            responseList.add(response);
            return response;
          }
        };
    CacheResult result =
        cache.fetch(
            new RuleKey("00000000000000000000000000000000"),
            Paths.get("output/file"));
    assertEquals(result.getType(), CacheResultType.MISS);
    assertTrue(
        "response wasn't fully read!",
        responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchOK() throws Exception {
    Path output = Paths.get("output/file");
    final String data = "test";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final List<Response> responseList = Lists.newArrayList();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            Response response =
                new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HttpURLConnection.HTTP_OK)
                    .body(
                        createResponseBody(
                            ImmutableSet.of(ruleKey),
                            ImmutableMap.<String, String>of(),
                            ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                            data))
                    .build();
            responseList.add(response);
            return response;
          }
        };
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(result.cacheError().or(""), CacheResultType.HIT, result.getType());
    assertEquals(Optional.of(data), filesystem.readFileIfItExists(output));
    assertTrue(
        "response wasn't fully read!",
        responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchUrl() throws Exception {
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final String expectedUri =
        "http://localhost:8080/artifacts/key/00000000000000000000000000000000";

    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            assertEquals(expectedUri, request.uri().toString());
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_OK)
                .body(
                    createResponseBody(
                        ImmutableSet.of(ruleKey),
                        ImmutableMap.<String, String>of(),
                        ByteSource.wrap(new byte[0]),
                        "data"))
                .build();
          }
        };
    cache.fetch(ruleKey, Paths.get("output/file"));
    cache.close();
  }

  @Test
  public void testFetchBadChecksum() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final List<Response> responseList = Lists.newArrayList();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            Response response =
                new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HttpURLConnection.HTTP_OK)
                    .body(
                        createResponseBody(
                            ImmutableSet.of(ruleKey),
                            ImmutableMap.<String, String>of(),
                            ByteSource.wrap(new byte[0]),
                            "data"))
                    .build();
            responseList.add(response);
            return response;
          }
        };
    Path output = Paths.get("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output));
    assertTrue(
        "response wasn't fully read!",
        responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchExtraPayload() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final List<Response> responseList = Lists.newArrayList();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            Response response =
                new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(HttpURLConnection.HTTP_OK)
                    .body(
                        createResponseBody(
                            ImmutableSet.of(ruleKey),
                            ImmutableMap.<String, String>of(),
                            ByteSource.wrap("more data than length".getBytes(Charsets.UTF_8)),
                            "small"))
                    .build();
            responseList.add(response);
            return response;
          }
        };
    Path output = Paths.get("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output));
    assertTrue(
        "response wasn't fully read!",
        responseList.get(0).body().source().exhausted());
    cache.close();
  }

  @Test
  public void testFetchIOException() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    Path output = Paths.get("output/file");
    CacheResult result = cache.fetch(new RuleKey("00000000000000000000000000000000"), output);
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output));
    cache.close();
  }

  @Test
  public void testStore() throws Exception {
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath(data, output);
    final AtomicBoolean hasCalled = new AtomicBoolean(false);
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response storeCall(Request request) throws IOException {
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
                      ImmutableMap.<String, String>of(),
                      ByteSource.wrap(data.getBytes(Charsets.UTF_8)));
              dataOut.writeInt(metadata.length);
              dataOut.write(metadata);
              dataOut.write(data.getBytes(Charsets.UTF_8));
              expectedData = out.toByteArray();
            }

            assertArrayEquals(expectedData, actualData);

            return new Response.Builder()
                .code(HttpURLConnection.HTTP_ACCEPTED)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .build();
          }
        };
    cache.storeImpl(
        ImmutableSet.of(ruleKey),
        ImmutableMap.<String, String>of(),
        output,
        createFinishedEventBuilder());
    assertTrue(hasCalled.get());
    cache.close();
  }

  @Test(expected = IOException.class)
  public void testStoreIOException() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath("data", output);
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    cache.storeImpl(
        ImmutableSet.of(new RuleKey("00000000000000000000000000000000")),
        ImmutableMap.<String, String>of(),
        output,
        createFinishedEventBuilder());
    cache.close();
  }

  @Test
  public void testStoreMultipleKeys() throws Exception {
    final RuleKey ruleKey1 = new RuleKey("00000000000000000000000000000000");
    final RuleKey ruleKey2 = new RuleKey("11111111111111111111111111111111");
    final String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path output = Paths.get("output/file");
    filesystem.writeContentsToPath(data, output);
    final Set<RuleKey> stored = Sets.newHashSet();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            Buffer buf = new Buffer();
            request.body().writeTo(buf);
            try (DataInputStream in =
                     new DataInputStream(new ByteArrayInputStream(buf.readByteArray()))) {
              int keys = in.readInt();
              for (int i = 0; i < keys; i++) {
                stored.add(new RuleKey(in.readUTF()));
              }
            }
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_ACCEPTED)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .build();
          }
        };
    cache.storeImpl(
        ImmutableSet.of(ruleKey1, ruleKey2),
        ImmutableMap.<String, String>of(),
        output,
        createFinishedEventBuilder());
    assertThat(
        stored,
        Matchers.containsInAnyOrder(ruleKey1, ruleKey2));
    cache.close();
  }

  @Test
  public void testFetchWrongKey() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final RuleKey otherRuleKey = new RuleKey("11111111111111111111111111111111");
    final String data = "data";
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_OK)
                .body(
                    createResponseBody(
                        ImmutableSet.of(otherRuleKey),
                        ImmutableMap.<String, String>of(),
                        ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                        data))
                .build();
          }
        };
    Path output = Paths.get("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResultType.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output));
    cache.close();
  }

  @Test
  public void testFetchMetadata() throws Exception {
    Path output = Paths.get("output/file");
    final String data = "test";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URI("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_OK)
                .body(
                    createResponseBody(
                        ImmutableSet.of(ruleKey),
                        metadata,
                        ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                        data))
                .build();
          }
        };
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResultType.HIT, result.getType());
    assertEquals(metadata, result.getMetadata());
    cache.close();
  }

}
