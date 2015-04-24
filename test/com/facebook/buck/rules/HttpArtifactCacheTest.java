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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.ByteStreams;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import okio.Buffer;

public class HttpArtifactCacheTest {

  private static final BuckEventBus BUCK_EVENT_BUS =
      new BuckEventBus(new IncrementingFakeClock(), new BuildId());
  private static final HashFunction HASH_FUNCTION = Hashing.crc32();

  private byte[] createArtifact(
      String key,
      long length,
      HashCode code,
      String contents)
      throws IOException {
    ByteArrayOutputStream byteSteam = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(byteSteam);
    output.writeUTF(key);
    output.writeLong(length);
    output.write(contents.getBytes(Charsets.UTF_8));
    output.write(code.asBytes());
    return byteSteam.toByteArray();
  }

  private byte[] createArtifact(String key, HashCode code, String contents) throws IOException {
    return createArtifact(
        key,
        contents.getBytes(Charsets.UTF_8).length,
        code,
        contents);
  }

  private byte[] createArtifact(String key, int length, String contents) throws IOException {
    try (HashingOutputStream hasher =
             new HashingOutputStream(HASH_FUNCTION, ByteStreams.nullOutputStream());
        DataOutputStream output = new DataOutputStream(hasher)) {
      output.writeUTF(key);
      output.writeLong(length);
      output.write(contents.getBytes(Charsets.UTF_8));
      return createArtifact(
          key,
          length,
          hasher.hash(),
          contents);
    }
  }

  private byte[] createArtifact(String key, String contents) throws IOException {
    return createArtifact(
        key,
        contents.length(),
        contents);
  }

  private ResponseBody createBody(String key, HashCode code, String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(key, code, contents));
  }

  private ResponseBody createBody(String key, int length, String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(key, length, contents));
  }

  private ResponseBody createBody(String key, String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(key, contents));
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
                .code(HttpURLConnection.HTTP_OK)
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body(createBody(ruleKey.toString(), data))
                .build();
          }
        };
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.Type.HIT, result.getType());
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
            assertEquals(new URL(url, "artifact/key/" + ruleKey.toString()), request.url());
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(ruleKey.toString(), "data"))
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
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(ruleKey.toString(), HashCode.fromInt(0), "test"))
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
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(ruleKey.toString(), 4, "more data than length"))
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
            assertArrayEquals(createArtifact(ruleKey.toString(), data), buf.readByteArray());
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_ACCEPTED)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .build();
          }
        };
    cache.store(ruleKey, output);
    assertTrue(hasCalled.get());
    cache.close();
  }

  @Test
  public void testStoreIOException() throws Exception {
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
    cache.store(new RuleKey("00000000000000000000000000000000"), output);
    cache.close();
  }

  @Test
  public void testFetchWrongKey() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final RuleKey otherRuleKey = new RuleKey("11111111111111111111111111111111");
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
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(otherRuleKey.toString(), "test"))
                .build();
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.Type.ERROR, result.getType());
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

}
