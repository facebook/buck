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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.hash.HasherInputStream;
import com.facebook.buck.util.hash.HasherOutputStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import org.immutables.value.Value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Implements the binary protocol used by Buck to talk to the cache server.
 */
public class HttpArtifactCacheBinaryProtocol {

  private static final HashFunction HASH_FUNCTION = Hashing.crc32();

  private HttpArtifactCacheBinaryProtocol() {
    // Utility class, don't instantiate.
  }

  public static FetchResponseReadResult readFetchResponse(
      InputStream byteStream,
      OutputStream payloadSink) throws IOException {
    FetchResponseReadResult.Builder result = FetchResponseReadResult.builder();

    // Create a hasher to be used to generate a hash of the metadata and input.  We'll use
    // this to compare against the embedded checksum.
    Hasher hasher = HASH_FUNCTION.newHasher();

    // Open the input stream from the server and start processing data.
    try (DataInputStream input = new DataInputStream(byteStream)) {

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
          int size = metadataIn.readInt();
          for (int i = 0; i < size; i++) {
            result.addRuleKeys(new RuleKey(metadataIn.readUTF()));
          }

          // Read in the actual metadata map, and add it the hash.
          size = metadataIn.readInt();
          for (int i = 0; i < size; i++) {
            String key = metadataIn.readUTF();
            int valSize = metadataIn.readInt();
            byte[] val = new byte[valSize];
            ByteStreams.readFully(metadataIn, val);
            result.putMetadata(key, new String(val, Charsets.UTF_8));
          }
        }

        // Next, read in the embedded expected checksum, which should be the last byte in
        // the metadata header.
        byte[] hashCodeBytes = new byte[HASH_FUNCTION.bits() / Byte.SIZE];
        ByteStreams.readFully(rawMetadataIn, hashCodeBytes);
        result.setExpectedHashCode(HashCode.fromBytes(hashCodeBytes));
      }

      // The remaining data is the payload, which we write to the created file, and also include
      // in our verification checksum.
      Hasher artifactOnlyHasher = HASH_FUNCTION.newHasher();
      try (InputStream payload = new HasherInputStream(artifactOnlyHasher,
          new HasherInputStream(hasher, byteStream))) {
        result.setResponseSizeBytes(ByteStreams.copy(payload, payloadSink));
        result.setArtifactOnlyHashCode(artifactOnlyHasher.hash());
      }

      result.setActualHashCode(hasher.hash());
    }

    return result.build();
  }

  public static class StoreRequest {
    private final ByteSource payloadSource;
    private final byte[] rawKeys;
    private final byte[] rawMetadata;
    private final long contentLength;

    public StoreRequest(
        ImmutableSet<RuleKey> ruleKeys,
        ImmutableMap<String, String> metadata,
        ByteSource payloadSource) throws IOException {
      this.payloadSource = payloadSource;
      this.rawKeys = createKeysHeader(ruleKeys);
      this.rawMetadata = createMetadataHeader(
          ruleKeys,
          metadata,
          payloadSource);
      this.contentLength =
          rawKeys.length +
          Integer.SIZE / Byte.SIZE +
          rawMetadata.length +
          payloadSource.size();
    }

    public long getContentLength() {
      return contentLength;
    }

    public StoreWriteResult write(OutputStream requestSink) throws IOException {
      StoreWriteResult.Builder result = StoreWriteResult.builder();
      try (DataOutputStream dataOutputStream = new DataOutputStream(requestSink)) {
        dataOutputStream.write(rawKeys);
        dataOutputStream.writeInt(rawMetadata.length);
        dataOutputStream.write(rawMetadata);
        Hasher hasher = HASH_FUNCTION.newHasher();
        try (InputStream is = new HasherInputStream(hasher, payloadSource.openBufferedStream())) {
          result.setArtifactSizeBytes(ByteStreams.copy(is, dataOutputStream));
          result.setArtifactContentHashCode(hasher.hash());
        }
      }
      return result.build();
    }
  }

  @VisibleForTesting
  static byte[] createKeysHeader(ImmutableSet<RuleKey> ruleKeys) throws IOException {
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
  static byte[] createMetadataHeader(
      ImmutableSet<RuleKey> ruleKeys,
      ImmutableMap<String, String> metadata,
      ByteSource data) throws IOException {

    ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
    Hasher hasher = HASH_FUNCTION.newHasher();
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

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractFetchResponseReadResult {
    public abstract ImmutableSet<RuleKey> getRuleKeys();
    public abstract HashCode getExpectedHashCode();
    public abstract HashCode getActualHashCode();
    public abstract HashCode getArtifactOnlyHashCode();
    public abstract long getResponseSizeBytes();
    public abstract ImmutableMap<String, String> getMetadata();
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractStoreWriteResult {
    public abstract HashCode getArtifactContentHashCode();
    public abstract long getArtifactSizeBytes();
  }
}
