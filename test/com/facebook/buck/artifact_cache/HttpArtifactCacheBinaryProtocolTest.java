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

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class HttpArtifactCacheBinaryProtocolTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCreateMetadataHeader() throws IOException {
    final String base64EncodedData =
        "AAAAAQAgMDAwMDAwMDAwMTAwMDAwMDAwMDAwMDgwMDAwMDAwMDAAAAABAANrZXkAAAAFdmFsdWVc/GBY";
    final RuleKey ruleKey = new RuleKey("00000000010000000000008000000000");
    final String data = "data";
    byte[] metadata =
        HttpArtifactCacheBinaryProtocol.createMetadataHeader(
            ImmutableSet.of(ruleKey),
            ImmutableMap.of("key", "value"),
            ByteSource.wrap(data.getBytes(Charsets.UTF_8)));
    assertThat(metadata, Matchers.equalTo(BaseEncoding.base64().decode(base64EncodedData)));
  }

  @Test
  public void testCreateKeysHeader() throws IOException {
    final String base64EncodedData =
        "AAAAAgAgMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAAIDkwMDAwMDAwMDAwMDAwMDAw" +
            "MDAwMDA4MDAwMDAwMDA1";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final RuleKey ruleKey2 = new RuleKey("90000000000000000000008000000005");
    byte[] keysHeader =
        HttpArtifactCacheBinaryProtocol.createKeysHeader(
            ImmutableSet.of(ruleKey, ruleKey2));
    assertThat(keysHeader, Matchers.equalTo(BaseEncoding.base64().decode(base64EncodedData)));
  }

  @Test
  public void testReadFetchResponse() throws IOException {
    final String base64EncodedData =
        "AAAALgAAAAEAIDAwMDAwMDAwMDEwMDAwMDAwMDAwMDA4MDAwMDAwMDAwAAAAANcwdr5kYXRh";
    final RuleKey ruleKey = new RuleKey("00000000010000000000008000000000");
    final String data = "data";

    byte[] expectedData;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         DataOutputStream dataOut = new DataOutputStream(out)) {
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
    assertThat(expectedData, Matchers.equalTo(BaseEncoding.base64().decode(base64EncodedData)));

    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
         DataInputStream inputStream =
             new DataInputStream(
                 new ByteArrayInputStream(expectedData))) {
      FetchResponseReadResult result = HttpArtifactCacheBinaryProtocol.readFetchResponse(
          inputStream,
          outputStream);
      assertThat(result.getRuleKeys(), Matchers.contains(ruleKey));
      assertThat(outputStream.toByteArray(), Matchers.equalTo(data.getBytes()));
      assertThat(result.getActualHashCode(), Matchers.equalTo(HashCode.fromString("d73076be")));
      assertThat(result.getExpectedHashCode(), Matchers.equalTo(HashCode.fromString("d73076be")));
      assertThat(result.getMetadata(), Matchers.anEmptyMap());
      assertThat(result.getResponseSizeBytes(), Matchers.equalTo(4L));
    }
  }

  @Test
  public void testMassiveMetadataHeaderWrite() throws IOException {
    ImmutableMap.Builder<String, String> metadataBuilder = ImmutableMap.builder();
    long metadataSize = 65 * 1024 * 1024;
    StringBuilder valueBuilder = new StringBuilder();
    for (int i = 0; i < 16384; ++i) {
      valueBuilder.append('x');
    }
    final String value = valueBuilder.toString();
    final long valueLength = value.getBytes(Charsets.UTF_8).length;

    while (metadataSize > 0) {
      String key = "key" + Long.toString(metadataSize);
      metadataSize -= key.getBytes(Charsets.UTF_8).length + valueLength;
      metadataBuilder.put(key, value);
    }

    thrown.expect(IOException.class);
    thrown.expectMessage(Matchers.containsString("too big"));
    HttpArtifactCacheBinaryProtocol.createMetadataHeader(
        ImmutableSet.of(new RuleKey("0000")),
        metadataBuilder.build(),
        ByteSource.wrap("wsad".getBytes(Charsets.UTF_8)));
  }

  @Test
  public void testMassiveMetadataHeaderRead() throws IOException {
    byte[] bytes;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         DataOutputStream dataOut = new DataOutputStream(out)) {
      dataOut.writeInt(Integer.MAX_VALUE);
      bytes = out.toByteArray();
    }

    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
      thrown.expect(IOException.class);
      thrown.expectMessage(Matchers.containsString("too big"));
      HttpArtifactCacheBinaryProtocol.readFetchResponse(
          new DataInputStream(inputStream),
          ByteStreams.nullOutputStream());
    }
  }

  @Test
  public void testStoreRequest() throws IOException {
    final String base64EncodedData = "AAAAAgAgMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAAIDkwMDA" +
        "wMDAwMDAwMDAwMDAwMDAwMDA4MDAwMDAwMDA1AAAAXgAAAAIAIDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA" +
        "wMDAwACA5MDAwMDAwMDAwMDAwMDAwMDAwMDAwODAwMDAwMDAwNQAAAAEAA2tleQAAAAV2YWx1ZRf0zcZkYXRhZGF" +
        "0YQ==";
    final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
    final RuleKey ruleKey2 = new RuleKey("90000000000000000000008000000005");

    HttpArtifactCacheBinaryProtocol.StoreRequest storeRequest =
        new HttpArtifactCacheBinaryProtocol.StoreRequest(
            ImmutableSet.of(ruleKey, ruleKey2),
            ImmutableMap.of("key", "value"),
            new ByteSource() {
              @Override
              public InputStream openStream() throws IOException {
                return new ByteArrayInputStream("datadata".getBytes());
              }
            });

    assertThat(storeRequest.getContentLength(), Matchers.is(178L));

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    StoreWriteResult writeResult = storeRequest.write(byteArrayOutputStream);
    assertThat(
        writeResult.getArtifactContentHashCode(),
        Matchers.equalTo(HashCode.fromString("2c0b14a4")));
    assertThat(writeResult.getArtifactSizeBytes(), Matchers.is(8L));
    byte[] expectedBytes = BaseEncoding.base64().decode(base64EncodedData);
    assertThat(byteArrayOutputStream.toByteArray(), Matchers.equalTo(expectedBytes));
  }
}
