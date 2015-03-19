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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.HashingInputStream;
import com.google.common.io.ByteStreams;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.apache.commons.compress.utils.BoundedInputStream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import okio.BufferedSink;

public class HttpArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final Logger LOGGER = Logger.get(HttpArtifactCache.class);
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private final OkHttpClient fetchClient;
  private final OkHttpClient storeClient;
  private final URL url;
  private final boolean doStore;
  private final ProjectFilesystem projectFilesystem;
  private final HashFunction hashFunction;

  public HttpArtifactCache(
      OkHttpClient fetchClient,
      OkHttpClient storeClient,
      URL url,
      boolean doStore,
      ProjectFilesystem projectFilesystem,
      HashFunction hashFunction) {
    this.fetchClient = fetchClient;
    this.storeClient = storeClient;
    this.url = url;
    this.doStore = doStore;
    this.projectFilesystem = projectFilesystem;
    this.hashFunction = hashFunction;
  }

  private Request.Builder createRequestBuilder(String key) throws IOException {
    return new Request.Builder()
        .url(new URL(url, "artifact/key/" + key));
  }

  protected Response fetchCall(Request request) throws IOException {
    return fetchClient.newCall(request).execute();
  }

  public CacheResult fetchImpl(RuleKey ruleKey, File file) throws IOException {
    Request request =
        createRequestBuilder(ruleKey.toString())
            .get()
            .build();
    Response response = fetchCall(request);

    if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
      LOGGER.info("fetch(%s): cache miss", ruleKey);
      return CacheResult.MISS;
    }

    if (response.code() != HttpURLConnection.HTTP_OK) {
      LOGGER.warn("fetch(%s): unexpected response: %d", ruleKey, response.code());
      return CacheResult.MISS;
    }

    // The hash code shipped with the artifact to/from the cache.
    HashCode expectedHashCode, actualHashCode;

    // Setup a temporary file, which sits next to the destination, to write to and
    // make sure all parent dirs exist.
    Path path = file.toPath();
    projectFilesystem.createParentDirs(path);
    Path temp = projectFilesystem.createTempFile(
        path.getParent(),
        path.getFileName().toString(),
        ".tmp");

    // Open the stream to server just long enough to read the hash code and artifact.
    try (DataInputStream input = new DataInputStream(response.body().byteStream())) {

      // First, extract the size of the file data portion, which we put in the beginning of
      // the artifact.
      long length = input.readLong();

      // Now, write the remaining response data to the temp file, while grabbing the hash.
      try (BoundedInputStream boundedInput = new BoundedInputStream(input, length);
           HashingInputStream hashingInput = new HashingInputStream(hashFunction, boundedInput);
           OutputStream output = projectFilesystem.newFileOutputStream(temp)) {
        ByteStreams.copy(hashingInput, output);
        actualHashCode = hashingInput.hash();
      }

      // Lastly, extract the hash code from the end of the request data.
      byte[] hashCodeBytes = new byte[hashFunction.bits() / Byte.SIZE];
      ByteStreams.readFully(input, hashCodeBytes);
      expectedHashCode = HashCode.fromBytes(hashCodeBytes);

      // We should be at the end of output -- verify this.  Also, we could just try to read a
      // single byte here, instead of all remaining input, but some network stack implementations
      // require that we exhaust the input stream before the connection can be reusable.
      try (OutputStream output = ByteStreams.nullOutputStream()) {
        if (ByteStreams.copy(input, output) != 0) {
          LOGGER.warn("fetch(%s): unexpected end of input", ruleKey);
          return CacheResult.MISS;
        }
      }
    }

    // Now form the checksum on the file we got and compare it to the checksum form the
    // the HTTP header.  If it's incorrect, log this and return a miss.
    if (!expectedHashCode.equals(actualHashCode)) {
      LOGGER.warn("fetch(%s): artifact had invalid checksum", ruleKey);
      projectFilesystem.deleteFileAtPath(temp);
      return CacheResult.MISS;
    }

    // Finally, move the temp file into it's final place.
    projectFilesystem.move(temp, path, StandardCopyOption.REPLACE_EXISTING);

    LOGGER.info("fetch(%s): cache hit", ruleKey);
    return CacheResult.HTTP_HIT;
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) throws InterruptedException {
    try {
      return fetchImpl(ruleKey, output);
    } catch (IOException e) {
      LOGGER.warn(e, "fetch(%s): IOException: %s", ruleKey, e.getMessage());
      return CacheResult.MISS;
    }
  }

  protected Response storeCall(Request request) throws IOException {
    return storeClient.newCall(request).execute();
  }

  public void storeImpl(RuleKey ruleKey, final File file) throws IOException {
    Request request = createRequestBuilder(ruleKey.toString())
        .put(
            new RequestBody() {
              @Override
              public MediaType contentType() {
                return OCTET_STREAM;
              }

              @Override
              public long contentLength() throws IOException {
                return
                    Long.SIZE / Byte.SIZE +
                    projectFilesystem.getFileSize(file.toPath()) +
                    hashFunction.bits() / Byte.SIZE;
              }

              @Override
              public void writeTo(BufferedSink sink) throws IOException {
                try (DataOutputStream output = new DataOutputStream(sink.outputStream());
                     InputStream input = projectFilesystem.newFileInputStream(file.toPath());
                     HashingInputStream hasher = new HashingInputStream(hashFunction, input)) {
                  output.writeLong(projectFilesystem.getFileSize(file.toPath()));
                  ByteStreams.copy(hasher, output);
                  output.write(hasher.hash().asBytes());
                }
              }
            })
        .build();

    Response response = storeCall(request);

    if (response.code() != HttpURLConnection.HTTP_ACCEPTED) {
      LOGGER.warn("store(%s): unexpected response: %d", ruleKey, response.code());
    }
  }

  @Override
  public void store(RuleKey ruleKey, File output) throws InterruptedException {
    if (!isStoreSupported()) {
      return;
    }
    try {
      storeImpl(ruleKey, output);
    } catch (IOException e) {
      LOGGER.warn(e, "store(%s): IOException: %s", ruleKey, e.getMessage());
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  @Override
  public void close() {}

}
