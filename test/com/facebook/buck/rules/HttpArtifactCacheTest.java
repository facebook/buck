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

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
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

  private static final HashFunction HASH_FUNCTION = Hashing.crc32();

  private byte[] createArtifact(long length, HashCode code, String contents) throws IOException {
    ByteArrayOutputStream byteSteam = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(byteSteam);
    output.writeLong(length);
    output.write(contents.getBytes(Charsets.UTF_8));
    output.write(code.asBytes());
    return byteSteam.toByteArray();
  }

  private byte[] createArtifact(HashCode code, String contents) throws IOException {
    return createArtifact(
        contents.getBytes(Charsets.UTF_8).length,
        code,
        contents);
  }

  private byte[] createArtifact(int length, String contents) throws IOException {
    return createArtifact(
        length,
        HASH_FUNCTION.hashString(contents, Charsets.UTF_8),
        contents);
  }

  private byte[] createArtifact(String contents) throws IOException {
    return createArtifact(HASH_FUNCTION.hashString(contents, Charsets.UTF_8), contents);
  }

  private ResponseBody createBody(HashCode code, String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(code, contents));
  }

  private ResponseBody createBody(int length, String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(length, contents));
  }

  private ResponseBody createBody(String contents) throws IOException {
    return ResponseBody.create(
        MediaType.parse("application/octet-stream"),
        createArtifact(contents));
  }

  @Test
  public void testFetchNotFound() throws Exception {
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
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
    assertEquals(result, CacheResult.MISS);
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
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body(createBody(data))
                .build();
          }
        };
    CacheResult result = cache.fetch(ruleKey, output);
    assertEquals(CacheResult.HTTP_HIT, result);
    assertEquals(Optional.of(data), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchUrl() throws Exception {
    final URL url = new URL("http://localhost:8080");
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            new FakeProjectFilesystem(),
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            assertEquals(new URL(url, "artifact/key/" + ruleKey.toString()), request.url());
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody("data"))
                .build();
          }
        };
    cache.fetch(ruleKey, new File("output/file"));
    cache.close();
  }

  @Test
  public void testFetchBadChecksum() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(HashCode.fromInt(0), "test"))
                .build();
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(new RuleKey("00000000000000000000000000000000"), output);
    assertEquals(CacheResult.MISS, result);
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchExtraPayload() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .body(createBody(4, "more data than length"))
                .build();
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(new RuleKey("00000000000000000000000000000000"), output);
    assertEquals(CacheResult.MISS, result);
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testFetchIOException() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response fetchCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    File output = new File("output/file");
    CacheResult result = cache.fetch(new RuleKey("00000000000000000000000000000000"), output);
    assertEquals(CacheResult.MISS, result);
    assertEquals(Optional.<String>absent(), filesystem.readFileIfItExists(output.toPath()));
    cache.close();
  }

  @Test
  public void testStore() throws Exception {
    final String data = "data";
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    File output = new File("output/file");
    filesystem.writeContentsToPath(data, output.toPath());
    final AtomicBoolean hasCalled = new AtomicBoolean(false);
    HttpArtifactCache cache =
        new HttpArtifactCache(
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            hasCalled.set(true);
            Buffer buf = new Buffer();
            request.body().writeTo(buf);
            assertArrayEquals(createArtifact(data), buf.readByteArray());
            return new Response.Builder()
                .code(HttpURLConnection.HTTP_ACCEPTED)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .build();
          }
        };
    cache.store(new RuleKey("00000000000000000000000000000000"), output);
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
            null,
            null,
            new URL("http://localhost:8080"),
            /* doStore */ true,
            filesystem,
            HASH_FUNCTION) {
          @Override
          protected Response storeCall(Request request) throws IOException {
            throw new IOException();
          }
        };
    cache.store(new RuleKey("00000000000000000000000000000000"), output);
    cache.close();
  }

}
