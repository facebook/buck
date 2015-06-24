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

package com.facebook.buck.rules;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
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
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okio.Buffer;

public class HttpArtifactCacheTest {

  private static final BuckEventBus BUCK_EVENT_BUS =
      new BuckEventBus(new IncrementingFakeClock(), new BuildId());
  private static final HashFunction HASH_FUNCTION = Hashing.crc32();
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
          HttpArtifactCache.createMetadataHeader(ruleKeys, metadata, source, HASH_FUNCTION);
      dataOut.writeInt(rawMetadata.length);
      dataOut.write(rawMetadata);
      dataOut.write(data.getBytes(Charsets.UTF_8));
      return ResponseBody.create(OCTET_STREAM, out.toByteArray());
    }
  }

  @Test
  public void testFetchNotFound() throws Exception {
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_NOT_FOUND)
                .body(ResponseBody.create(MediaType.parse("application/octet-stream"), ""))
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .build();
          }
        };
    CacheResult result =
        cache.fetch(
            new RuleKey("00000000000000000000000000000000"),
            new File("output/file"));
    assertEquals(result.getType(), CacheResult.Type.MISS);
    cache.close();
  }

  @Test
  public void testFetchOK() throws Exception {
    File output = new File("output/file");
    final String data = "test";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
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
          }
        };
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(result.cacheError().or(""), CacheResult.Type.HIT, result.getType());
    assertEquals(Optional.of(data), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchUrl() throws Exception {
    final URL url = new URL("http://localhost:8080");
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            assertEquals(new URL(url, "artifacts/key/" + ruleKey.toString()), request.url());
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
    cache.fetch(ruleKey, new File("output/file"));
    cache.close();
  }

  @Test
  public void testFetchBadChecksum() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
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
    File output = new File("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.Type.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchExtraPayload() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
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
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.Type.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
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
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(new RuleKey("00000000000000000000000000000000"), output);
    assertEquals(CacheResult.Type.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testStore() throws Exception {
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    File output = new File("output/file");
    filesystem.writeContentsToPath(data, output.toPath());
    final AtomicBoolean hasCalled = new AtomicBoolean(false);
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            hasCalled.set(true);

            Buffer buf = new Buffer();
            request.body().writeTo(buf);
            byte[] actualData = buf.readByteArray();

            byte[] expectedData;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DataOutputStream dataOut = new DataOutputStream(out)) {
              dataOut.write(HttpArtifactCache.createKeysHeader(ImmutableSet.of(ruleKey)));
              byte[] metadata =
                  HttpArtifactCache.createMetadataHeader(
                      ImmutableSet.of(ruleKey),
                      ImmutableMap.<String, String>of(),
                      ByteSource.wrap(data.getBytes(Charsets.UTF_8)),
                      HASH_FUNCTION);
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
    cache.storeImpl(ImmutableSet.of(ruleKey), ImmutableMap.<String, String>of(), output);
    assertTrue(hasCalled.get());
    cache.close();
  }

  @Test(expected = IOException.class)
  public void testStoreIOException() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    File output = new File("output/file");
    filesystem.writeContentsToPath("data", output.toPath());
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    cache.storeImpl(
        ImmutableSet.of(new RuleKey("00000000000000000000000000000000")),
        ImmutableMap.<String, String>of(),
        output);
    cache.close();
  }

  @Test
  public void testStoreMultipleKeys() throws Exception {
    final RuleKey ruleKey1 = new RuleKey("00000000000000000000000000000000");
    final RuleKey ruleKey2 = new RuleKey("11111111111111111111111111111111");
    final String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    File output = new File("output/file");
    filesystem.writeContentsToPath(data, output.toPath());
    final Set<RuleKey> stored = Sets.newHashSet();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
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
    cache.storeImpl(ImmutableSet.of(ruleKey1, ruleKey2), ImmutableMap.<String, String>of(), output);
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
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
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
    File output = new File("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.Type.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchMetadata() throws Exception {
    File output = new File("output/file");
    final String data = "test";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final ImmutableMap<String, String> metadata = ImmutableMap.of("some", "metadata");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            "http",
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            BUCK_EVENT_BUS,
            HASH_FUNCTION) {
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
    assertEquals(CacheResult.Type.HIT, result.getType());
    assertEquals(metadata, result.getMetadata());
    cache.close();
  }

}
