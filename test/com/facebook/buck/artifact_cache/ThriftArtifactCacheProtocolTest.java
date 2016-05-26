/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.io.ByteSource;

import org.apache.thrift.TException;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ThriftArtifactCacheProtocolTest {
  private static final ThriftProtocol PROTOCOL = ThriftArtifactCache.PROTOCOL;

  @Test
  public void testSendingRequest() throws IOException {
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, createDefaultRequest());

    long expectedBytes = request.getRequestLengthBytes();
    Assert.assertTrue(expectedBytes > 0);
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      Assert.assertEquals(expectedBytes, buffer.length);
    }
  }

  @Test
  public void testSendingRequestWithPayload() throws IOException {
    int payloadSizeBytes = 42;
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(
            PROTOCOL,
            createDefaultRequest(payloadSizeBytes),
            ByteSource.wrap(new byte[payloadSizeBytes]));

    long expectedBytes = request.getRequestLengthBytes();
    Assert.assertTrue(expectedBytes > 0);
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      Assert.assertEquals(expectedBytes, buffer.length);
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSendingRequestWithoutPayloadStream() throws IOException {
    int payloadSizeBytes = 42;
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, createDefaultRequest(payloadSizeBytes));

    long expectedBytes = request.getRequestLengthBytes();
    Assert.assertTrue(expectedBytes > 0);
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      Assert.assertEquals(expectedBytes, buffer.length);
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSendingRequestWithExtratPayloadStream() throws IOException {
    int payloadSizeBytes = 42;
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(
            PROTOCOL,
            createDefaultRequest(payloadSizeBytes),
            ByteSource.wrap(new byte[payloadSizeBytes]),
            ByteSource.wrap(new byte[payloadSizeBytes]));

    long expectedBytes = request.getRequestLengthBytes();
    Assert.assertTrue(expectedBytes > 0);
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      Assert.assertEquals(expectedBytes, buffer.length);
    }
  }

  @Test
  public void testReceivingDataWithPayload() throws IOException, TException {
    byte[] expectedPayload = createBuffer(21);
    String expectedErrorMessage = "My Super cool error message";
    BuckCacheResponse expectedResponse = null;
    byte[] responseRawData = null;
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      expectedResponse = serializeData(expectedErrorMessage, stream, expectedPayload);
      responseRawData = stream.toByteArray();
    }

    try (ByteArrayInputStream stream = new ByteArrayInputStream(responseRawData);
         ByteArrayOutputStream payloadStream = new ByteArrayOutputStream()) {
      ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, stream);

      BuckCacheResponse actualResponse = response.getThriftData();
      Assert.assertEquals(
          expectedResponse.getErrorMessage(),
          actualResponse.getErrorMessage());

      response.readPayload(payloadStream);
      byte[] actualPayload = payloadStream.toByteArray();
      Assert.assertEquals(expectedPayload.length, actualPayload.length);
      for (int i = 0; i < expectedPayload.length; ++i) {
        Assert.assertEquals(expectedPayload[i], actualPayload[i]);
      }
    }
  }

  @Test(expected =  IOException.class)
  public void testReceivingCorruptedData() throws IOException, TException {
    byte[] expectedPayload = createBuffer(21);
    byte[] responseRawData = null;
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      serializeData("irrelevant", stream, expectedPayload);
      responseRawData = stream.toByteArray();
    }

    try (ByteArrayInputStream stream =
             new ByteArrayInputStream(responseRawData, 0, responseRawData.length - 4);
         ByteArrayOutputStream payloadStream = new ByteArrayOutputStream()) {
      ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, stream);
      response.readPayload(payloadStream);
      Assert.fail("And exception should've been thrown trying to read the payload.");
    }
  }

  private byte[] createBuffer(int sizeBytes) {
    byte[] buffer = new byte[sizeBytes];
    for (int i = 0; i < sizeBytes; ++i) {
      buffer[i] = (byte) (i % Byte.MAX_VALUE);
    }

    return buffer;
  }

  // TODO(ruibm): Use Base64 response data generated from the real server implementation.
  private BuckCacheResponse serializeData(
      String errorMessage,
      OutputStream rawStream,
      byte[]... payloads) throws IOException, TException {
    BuckCacheResponse cacheResponse = new BuckCacheResponse();
    cacheResponse.setErrorMessage(errorMessage);
    for (byte[] payload : payloads) {
      PayloadInfo info = new PayloadInfo();
      info.setSizeBytes(payload.length);
      cacheResponse.addToPayloads(info);
    }

    try (DataOutputStream stream = new DataOutputStream(rawStream)) {
      byte[] header = ThriftUtil.serialize(PROTOCOL, cacheResponse);
      stream.writeInt(header.length);
      stream.write(header);
      for (byte[] payload : payloads) {
        stream.write(payload);
      }

      stream.flush();
    }

    return cacheResponse;
  }

  private BuckCacheRequest createDefaultRequest(long... payloadSizeBytes) {
    BuckCacheRequest cacheRequest = new BuckCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.FETCH);
    for (long sizeBytes : payloadSizeBytes) {
      PayloadInfo info = new PayloadInfo();
      info.setSizeBytes(sizeBytes);
      cacheRequest.addToPayloads(info);
    }

    return cacheRequest;
  }
}
