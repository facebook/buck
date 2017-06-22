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
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * All messages generate by this Protocol will be in the following binary format: - int32 Big Endian
 * size bytes of thrift serialized thriftData. - Thrift serialized thriftData. - Remainder of the
 * stream contains binary payload data. Information about it is available in the Thrift thriftData.
 */
public class ThriftArtifactCacheProtocol {

  private static final Logger LOG = Logger.get(ThriftArtifactCacheProtocol.class);

  private static final HashFunction MD5_HASH_FUNCTION = Hashing.md5();

  private ThriftArtifactCacheProtocol() {
    // Not instantiable.
  }

  public static Request createRequest(
      ThriftProtocol protocol, BuckCacheRequest request, ByteSource... payloadByteSources)
      throws IOException {
    return new Request(protocol, request, payloadByteSources);
  }

  public static Response parseResponse(ThriftProtocol protocol, InputStream responseStream)
      throws IOException {
    return new Response(protocol, responseStream);
  }

  public static String computeMd5Hash(ByteSource source) throws IOException {
    return computeHash(source, MD5_HASH_FUNCTION);
  }

  private static String computeHash(ByteSource source, HashFunction hashFunction)
      throws IOException {
    try (InputStream inputStream = source.openStream();
        HashingOutputStream outputStream =
            new HashingOutputStream(
                hashFunction,
                new OutputStream() {
                  @Override
                  public void write(int b) throws IOException {
                    // Do nothing.
                  }
                })) {
      ByteStreams.copy(inputStream, outputStream);
      return outputStream.hash().toString();
    }
  }

  // TODO(ruibm): Via interface we can make this class generic on ThrifTypeT. Do that when required.
  public static class Request {
    private final byte[] serializedThriftData;
    private final ImmutableList<PayloadInfo> payloads;
    private final long totalPayloadBytes;
    private final ByteSource[] payloadByteSources;

    private Request(
        ThriftProtocol protocol, BuckCacheRequest thriftData, ByteSource... payloadByteSources)
        throws IOException {
      this.payloads =
          thriftData.isSetPayloads()
              ? ImmutableList.copyOf(thriftData.getPayloads())
              : ImmutableList.of();

      assertTrue(
          payloadByteSources.length == this.payloads.size(),
          "Number of payloadStreams provided [%s] does not match number of payloads "
              + "in the thriftData [%d].",
          payloadByteSources.length,
          payloads.size());

      this.payloadByteSources = payloadByteSources;

      long payloadBytes = 0;
      for (PayloadInfo info : payloads) {
        payloadBytes += info.getSizeBytes();
      }
      this.totalPayloadBytes = payloadBytes;

      serializedThriftData = ThriftUtil.serialize(protocol, thriftData);
    }

    public long getRequestLengthBytes() {
      return (Integer.SIZE / Byte.SIZE) + serializedThriftData.length + totalPayloadBytes;
    }

    public void writeAndClose(OutputStream rawStream) throws IOException {

      try (DataOutputStream outStream = new DataOutputStream(rawStream)) {
        outStream.writeInt(serializedThriftData.length);
        outStream.write(serializedThriftData);
        for (int i = 0; i < payloads.size(); ++i) {
          try (InputStream inputStream = payloadByteSources[i].openStream()) {
            PayloadInfo info = payloads.get(i);
            copyExactly(inputStream, outStream, info.getSizeBytes());
          }
        }
      }
    }

    @Override
    public String toString() {
      return "Request{"
          + "serializedThriftData="
          + Arrays.toString(serializedThriftData)
          + ", payloads="
          + payloads
          + ", totalPayloadBytes="
          + totalPayloadBytes
          + ", payloadByteSources="
          + Arrays.toString(payloadByteSources)
          + '}';
    }
  }

  public static class Response implements Closeable {
    private final BuckCacheResponse thriftData;
    private final DataInputStream responseStream;

    private int nextPayloadToBeRead;

    public Response(ThriftProtocol protocol, InputStream rawStream) throws IOException {
      this.nextPayloadToBeRead = 0;
      this.responseStream = new DataInputStream(rawStream);
      this.thriftData = new BuckCacheResponse();

      int thriftByteSize = this.responseStream.readInt();
      byte[] thriftData = new byte[thriftByteSize];
      this.responseStream.readFully(thriftData);

      try {
        ThriftUtil.deserialize(protocol, thriftData, this.thriftData);
      } catch (IOException e) {
        String message =
            String.format(
                "Failed to deserialize [%d] bytes of BuckCacheFetchResponse.", thriftByteSize);
        LOG.error(message);
        throw new IOException(message);
      }
    }

    public BuckCacheResponse getThriftData() {
      return thriftData;
    }

    public ReadPayloadInfo readPayload(OutputStream outStream) throws IOException {
      assertTrue(
          nextPayloadToBeRead < thriftData.getPayloadsSize(),
          "Trying to download payload index=[%s] but the thriftData only contains [%s] payloads.",
          nextPayloadToBeRead,
          thriftData.getPayloadsSize());

      long payloadSizeBytes =
          assertNotNull(thriftData.getPayloads(), "Payloads[] cannot be null.")
              .get(nextPayloadToBeRead)
              .getSizeBytes();
      try (HashingOutputStream wrappedOutputStream =
          new HashingOutputStream(MD5_HASH_FUNCTION, outStream)) {
        copyExactly(responseStream, wrappedOutputStream, payloadSizeBytes);
        ++nextPayloadToBeRead;
        return new ReadPayloadInfo(payloadSizeBytes, wrappedOutputStream.hash().toString());
      }
    }

    @Override
    public void close() throws IOException {
      responseStream.close();
      nextPayloadToBeRead = -1;
    }

    public static class ReadPayloadInfo {
      private final long bytesRead;
      private final String md5Hash;

      public ReadPayloadInfo(long bytesRead, String md5Hash) {
        this.bytesRead = bytesRead;
        this.md5Hash = md5Hash;
      }

      public long getBytesRead() {
        return bytesRead;
      }

      public String getMd5Hash() {
        return md5Hash;
      }
    }
  }

  /**
   * Copy an exact number of bytes between two streams, failing if source has fewer bytes than
   * requested.
   *
   * @param source Stream to copy from.
   * @param destination Stream to copy to.
   * @param bytesToRead Number of bytes to copy.
   * @throws IOException if an I/O error occcurs, or the source stream has fewer bytes than
   *     requested.
   */
  @VisibleForTesting
  static void copyExactly(InputStream source, OutputStream destination, long bytesToRead)
      throws IOException {
    long bytesCopied = ByteStreams.copy(ByteStreams.limit(source, bytesToRead), destination);
    if (bytesCopied < bytesToRead) {
      String msg =
          String.format(
              "InputStream was missing [%d] bytes. Expected to read a total of [%d] bytes.",
              bytesToRead - bytesCopied, bytesToRead);
      LOG.error(msg);
      throw new IOException(msg);
    }
  }

  public static class ProtocolException extends IOException {
    public ProtocolException(String message) {
      super(message);
    }
  }

  private static void assertTrue(boolean condition, String msgTemplate, Object... msgArgs)
      throws ProtocolException {
    if (!condition) {
      throw new ProtocolException(String.format(msgTemplate, msgArgs));
    }
  }

  private static <T> T assertNotNull(@Nullable T object, String message) throws ProtocolException {
    if (object == null) {
      throw new ProtocolException(message);
    }

    return object;
  }
}
