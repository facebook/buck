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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.hash.HasherInputStream;
import com.facebook.buck.util.hash.HasherOutputStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Map;
import java.util.Set;

import okio.BufferedSink;
import okio.BufferedSource;

public class HttpArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final Logger LOGGER = Logger.get(HttpArtifactCache.class);
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private final String name;
  private final OkHttpClient fetchClient;
  private final OkHttpClient storeClient;
  private final URL url;
  private final boolean doStore;
  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final HashFunction hashFunction;

  private final Set<String> seenErrors = Sets.newConcurrentHashSet();

  public HttpArtifactCache(
      String name,
      OkHttpClient fetchClient,
      OkHttpClient storeClient,
      URL url,
      boolean doStore,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      HashFunction hashFunction) {
    this.name = name;
    this.fetchClient = fetchClient;
    this.storeClient = storeClient;
    this.url = url;
    this.doStore = doStore;
    this.projectFilesystem = projectFilesystem;
    this.buckEventBus = buckEventBus;
    this.hashFunction = hashFunction;
  }

  protected Response fetchCall(Request request) throws IOException {
    return fetchClient.newCall(request).execute();
  }

  // In order for connections to get reused, we need to reach EOF on their input streams.
  // This is a convenience method to consume all remaining bytes from the given input stream
  // for this purpose.
  private long readTillEnd(InputStream input) throws IOException {
    try (OutputStream oblivion = ByteStreams.nullOutputStream()) {
      return ByteStreams.copy(input, oblivion);
    }
  }

  private long readTillEnd(Response response) throws IOException {
    try (InputStream input = response.body().byteStream()) {
      return readTillEnd(input);
    }
  }

  public CacheResult fetchImpl(RuleKey ruleKey, File file) throws IOException {
    Request request =
        new Request.Builder()
            .url(new URL(url, "artifacts/key/" + ruleKey.toString()))
            .get()
            .build();
    Response response = fetchCall(request);

    if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
      readTillEnd(response);
      LOGGER.info("fetch(%s, %s): cache miss", url, ruleKey);
      return CacheResult.miss();
    }

    if (response.code() != HttpURLConnection.HTTP_OK) {
      readTillEnd(response);
      String msg = String.format("unexpected response: %d", response.code());
      reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
      return CacheResult.error(name, msg);
    }

    // Create a hasher to be used to generate a hash of the metadata and input.  We'll use
    // this to compare against the embedded checksum.
    Hasher hasher = hashFunction.newHasher();

    // The metadata that we'll re-materialize from the raw metadata header.
    ImmutableMap<String, String> metadata;

    // The expected hash code embedded in the returned data.
    HashCode expectedHashCode;

    // Setup a temporary file, which sits next to the destination, to write to and
    // make sure all parent dirs exist.
    Path path = file.toPath();
    projectFilesystem.createParentDirs(path);
    Path temp = projectFilesystem.createTempFile(
        path.getParent(),
        path.getFileName().toString(),
        ".tmp");

    // Open the input stream from the server and start processing data.
    try (DataInputStream input = new DataInputStream(response.body().byteStream())) {

      // Read the size of a the metadata, and use that to build a input stream to read and
      // process the rest of it.
      int metadataSize = input.readInt();
      byte[] rawMetadata = new byte[metadataSize];
      ByteStreams.readFully(input, rawMetadata);
      try (InputStream rawMetadataIn = new ByteArrayInputStream(rawMetadata)) {

        // The first part of the metadata needs to be included in the hash.
        try (DataInputStream metadataIn =
                 new DataInputStream(new HasherInputStream(hasher, rawMetadataIn))) {

          // Read in the rule keys that stored this artifact, and add them to the hash we're
          // building up.
          Set<RuleKey> ruleKeys = Sets.newHashSet();
          int size = metadataIn.readInt();
          for (int i = 0; i < size; i++) {
            ruleKeys.add(new RuleKey(metadataIn.readUTF()));
          }

          // Verify that we were one of the rule keys that stored this artifact.
          if (!ruleKeys.contains(ruleKey)) {
            readTillEnd(response);
            String msg = "incorrect key name";
            reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
            return CacheResult.error(name, msg);
          }

          // Read in the actual metadata map, and add it the hash.
          ImmutableMap.Builder<String, String> metadataBuilder = ImmutableMap.builder();
          size = metadataIn.readInt();
          for (int i = 0; i < size; i++) {
            String key = metadataIn.readUTF();
            int valSize = metadataIn.readInt();
            byte[] val = new byte[valSize];
            ByteStreams.readFully(metadataIn, val);
            metadataBuilder.put(key, new String(val, Charsets.UTF_8));
          }
          metadata = metadataBuilder.build();
        }

        // Next, read in the embedded expected checksum, which should be the last byte in
        // the metadata header.
        byte[] hashCodeBytes = new byte[hashFunction.bits() / Byte.SIZE];
        ByteStreams.readFully(rawMetadataIn, hashCodeBytes);
        expectedHashCode = HashCode.fromBytes(hashCodeBytes);
      }

      // The remaining data is the payload, which we write to the created file, and also include
      // in our verification checksum.
      try (InputStream payload = new HasherInputStream(hasher, response.body().byteStream());
           OutputStream output = projectFilesystem.newFileOutputStream(temp)) {
        ByteStreams.copy(payload, output);
      }
    }

    // Compute the hash now that we've processed the relevant parts of the artifact -- only
    // the expected hash remains.
    HashCode actualHashCode = hasher.hash();

    // Now form the checksum on the file we got and compare it to the checksum form the
    // the HTTP header.  If it's incorrect, log this and return a miss.
    if (!expectedHashCode.equals(actualHashCode)) {
      String msg = "artifact had invalid checksum";
      reportFailure("fetch(%s, %s): %s", url, ruleKey, msg);
      projectFilesystem.deleteFileAtPath(temp);
      return CacheResult.error(name, msg);
    }

    // Finally, move the temp file into it's final place.
    projectFilesystem.move(temp, path, StandardCopyOption.REPLACE_EXISTING);

    LOGGER.info("fetch(%s, %s): cache hit", url, ruleKey);
    return CacheResult.hit(name, metadata);
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) throws InterruptedException {
    try {
      return fetchImpl(ruleKey, output);
    } catch (IOException e) {
      String msg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
      reportFailure(e, "fetch(%s, %s): %s", url, ruleKey, msg);
      return CacheResult.error(name, msg);
    }
  }

  protected Response storeCall(Request request) throws IOException {
    return storeClient.newCall(request).execute();
  }

  @VisibleForTesting
  protected static byte[] createKeysHeader(ImmutableSet<RuleKey> ruleKeys) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         DataOutputStream data = new DataOutputStream(out)) {
      data.writeInt(ruleKeys.size());
      for (RuleKey ruleKey : ruleKeys) {
        data.writeUTF(ruleKey.toString());
      }
      return out.toByteArray();
    }
  }

  @VisibleForTesting
  protected static byte[] createMetadataHeader(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      ByteSource data,
      HashFunction hashFunction)
      throws IOException {

    ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
    Hasher hasher = hashFunction.newHasher();
    try (DataOutputStream out = new DataOutputStream(new HasherOutputStream(hasher, rawOut))) {

      // Write the rule keys to the raw metadata, including them in the end-to-end checksum.
      out.writeInt(ruleKeys.size());
      for (RuleKey ruleKey : ruleKeys) {
        out.writeUTF(ruleKey.toString());
      }

      // Write out the metadata map to the raw metadata, including it in the end-to-end checksum.
      out.writeInt(metadata.size());
      for (Map.Entry<String, String> ent : metadata.entrySet()) {
        out.writeUTF(ent.getKey());
        byte[] val = ent.getValue().getBytes(Charsets.UTF_8);
        out.writeInt(val.length);
        out.write(val);
      }
    }

    // Add the file data contents to the end-to-end checksum.
    data.copyTo(new HasherOutputStream(hasher, ByteStreams.nullOutputStream()));

    // Finish the checksum, adding it to the raw metadata
    rawOut.write(hasher.hash().asBytes());

    // Finally, base64 encode the raw bytes to make usable in a HTTP header.
    return rawOut.toByteArray();
  }

  protected void storeImpl(
      ImmutableSet<RuleKey> ruleKeys,
      final ImmutableMap<String, String> metadata,
      final File file)
      throws IOException {

    // Build the request, hitting the multi-key endpoint.
    Request.Builder builder = new Request.Builder();
    builder.url(new URL(url, "artifacts/key"));

    // Construct the raw keys blob;
    final byte[] rawKeys = createKeysHeader(ruleKeys);

    // Construct the raw metadata blob;
    final byte[] rawMetadata =
        createMetadataHeader(
            ruleKeys,
            metadata,
            new ByteSource() {
              @Override
              public InputStream openStream() throws IOException {
                return projectFilesystem.newFileInputStream(file.toPath());
              }
            },
            hashFunction);

    // Wrap the file into a `RequestBody` which uses `ProjectFilesystem`.
    builder.put(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return OCTET_STREAM;
          }

          @Override
          public long contentLength() throws IOException {
            return
                rawKeys.length +
                Integer.SIZE / Byte.SIZE +
                rawMetadata.length +
                projectFilesystem.getFileSize(file.toPath());
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            bufferedSink.write(rawKeys);
            bufferedSink.writeInt(rawMetadata.length);
            bufferedSink.write(rawMetadata);
            try (BufferedSource fileSource = projectFilesystem.newSource(file.toPath())) {
              bufferedSink.writeAll(fileSource);
            }
          }
        });

    // Dispatch the store operation and verify it succeeded.
    Request request = builder.build();
    Response response = storeCall(request);
    if (response.code() != HttpURLConnection.HTTP_ACCEPTED) {
      reportFailure("store(%s, %s): unexpected response: %d", url, ruleKeys, response.code());
    }
  }

  @Override
  public void store(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      File output)
      throws InterruptedException {
    if (!isStoreSupported()) {
      return;
    }
    try {
      storeImpl(ruleKeys, metadata, output);
    } catch (IOException e) {
      reportFailure(
          e,
          "store(%s, %s): %s: %s",
          url,
          ruleKeys,
          e.getClass().getName(),
          e.getMessage());
    }
  }

  private void reportFailure(Exception exception, String format, Object... args) {
    LOGGER.warn(exception, format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailure(String format, Object... args) {
    LOGGER.warn(format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailureToEvenBus(String format, Object... args) {
    if (seenErrors.add(format)) {
      buckEventBus.post(ConsoleEvent.warning(format, args));
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  @Override
  public void close() {}

}
