/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.slb;

import com.facebook.buck.distributed.thrift.Digest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class HybridThriftOverHttpServiceImplTest {
  private static final ThriftProtocol PROTOCOL = ThriftProtocol.COMPACT;

  @Test
  public void testWriteReadingDataWithoutPayload() throws IOException {
    Digest digestOriginal = new Digest().setHash("hello").setSizeBytes(42);
    byte[] data = null;
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(outputStream)) {
      HybridThriftOverHttpServiceImpl.writeToStream(
          dataStream,
          ThriftUtil.serialize(PROTOCOL, digestOriginal),
          HybridThriftRequestHandler.createWithoutPayloads(digestOriginal));

      data = outputStream.toByteArray();
      Assert.assertEquals(14, data.length);
    }

    Digest digestResponse = new Digest();
    HybridThriftOverHttpServiceImpl.readFromStream(
        toDataInputStream(new ByteArrayInputStream(data)),
        PROTOCOL,
        HybridThriftResponseHandler.createNoPayloadHandler(digestResponse));

    Assert.assertEquals(digestOriginal, digestResponse);
  }

  @Test
  public void testWriteReadingDataWithMultiplePayloads() throws IOException {
    Digest digestOriginal = new Digest().setHash("viva").setSizeBytes(21);
    final byte[] payload1 = "super".getBytes();
    final byte[] payload2 = "cool".getBytes();
    byte[] data = null;
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(outputStream)) {
      HybridThriftOverHttpServiceImpl.writeToStream(
          dataStream,
          ThriftUtil.serialize(PROTOCOL, digestOriginal),
          new HybridThriftRequestHandler(digestOriginal) {
            @Override
            public long getTotalPayloadsSizeBytes() {
              return payload1.length + payload2.length;
            }

            @Override
            public int getNumberOfPayloads() {
              return 2;
            }

            @Override
            public InputStream getPayloadStream(int index) {
              switch (index) {
                case 0:
                  return new ByteArrayInputStream(payload1);
                case 1:
                  return new ByteArrayInputStream(payload2);
                default:
                  throw new IllegalStateException();
              }
            }
          });

      data = outputStream.toByteArray();
      Assert.assertEquals(22, data.length);
    }

    Digest digestResponse = new Digest();
    ByteArrayOutputStream payload1Actual = new ByteArrayOutputStream();
    ByteArrayOutputStream payload2Actual = new ByteArrayOutputStream();
    try (DataInputStream inputStream = toDataInputStream(new ByteArrayInputStream(data))) {
      HybridThriftOverHttpServiceImpl.readFromStream(
          inputStream,
          PROTOCOL,
          new HybridThriftResponseHandler<Digest>(digestResponse) {
            @Override
            public int getTotalPayloads() {
              return 2;
            }

            @Override
            public long getPayloadSizeBytes(int index) {
              switch (index) {
                case 0:
                  return payload1.length;
                case 1:
                  return payload2.length;
                default:
                  throw new IllegalStateException();
              }
            }

            @Override
            public OutputStream getStreamForPayload(int index) {
              switch (index) {
                case 0:
                  return payload1Actual;
                case 1:
                  return payload2Actual;
                default:
                  throw new IllegalStateException();
              }
            }
          });
    }

    Assert.assertEquals(digestOriginal, digestResponse);
    Assert.assertTrue(Arrays.equals(payload1, payload1Actual.toByteArray()));
    Assert.assertTrue(Arrays.equals(payload2, payload2Actual.toByteArray()));
  }

  private static DataInputStream toDataInputStream(InputStream innerStream) {
    InputStream readAfterCloseThrowsStream =
        new FilterInputStream(innerStream) {
          private boolean isClosed = false;

          @Override
          public int read() throws IOException {
            assertNotClosed();
            return super.read();
          }

          @Override
          public int read(byte[] b) throws IOException {
            assertNotClosed();
            return super.read(b);
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            assertNotClosed();
            return super.read(b, off, len);
          }

          private void assertNotClosed() {
            if (isClosed) {
              Assert.fail("Stream already closed.");
            }
          }

          @Override
          public void close() throws IOException {
            super.close();
            isClosed = true;
          }
        };

    return new DataInputStream(readAfterCloseThrowsStream);
  }
}
