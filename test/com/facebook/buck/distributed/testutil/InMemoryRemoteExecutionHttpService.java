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

package com.facebook.buck.distributed.testutil;

import com.facebook.buck.distributed.RemoteExecutionStorageService;
import com.facebook.buck.distributed.thrift.Digest;
import com.facebook.buck.distributed.thrift.DigestAndContent;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.RemoteExecutionContainsResponse;
import com.facebook.buck.distributed.thrift.RemoteExecutionFetchResponse;
import com.facebook.buck.distributed.thrift.RemoteExecutionStoreResponse;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.HybridThriftOverHttpService;
import com.facebook.buck.slb.HybridThriftOverHttpServiceImpl;
import com.facebook.buck.slb.HybridThriftOverHttpServiceImplArgs;
import com.facebook.buck.slb.HybridThriftRequestHandler;
import com.facebook.buck.slb.HybridThriftResponseHandler;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.Request.Builder;
import okio.Buffer;

/**
 * HtppService that handles all Request/Responses required for RemoteExecutionStorage preserving all
 * data in memory.
 */
public class InMemoryRemoteExecutionHttpService implements HttpService {

  private static ThriftProtocol PROTOCOL = ThriftProtocol.COMPACT;
  private static String FAKE_URL = "http://localhost";

  private final Map<Digest, byte[]> storage;

  private static class ResponseHandler extends HybridThriftResponseHandler<FrontendRequest> {

    private final Map<Digest, ByteArrayOutputStream> payloads;

    protected ResponseHandler(FrontendRequest emptyResponse) {
      super(emptyResponse);
      payloads = Maps.newHashMap();
    }

    public Map<Digest, byte[]> getPayloads() {
      return payloads
          .entrySet()
          .stream()
          .collect(
              Collectors.toMap(
                  keyValue -> keyValue.getKey(), keyValue -> keyValue.getValue().toByteArray()));
    }

    @Override
    public int getTotalPayloads() {
      if (getResponse().getType() == FrontendRequestType.REMOTE_EXECUTION_STORE) {
        return getResponse().getRemoteExecutionStoreRequest().getDigestsSize();
      }

      return 0;
    }

    @Override
    public long getPayloadSizeBytes(int payloadIndex) {
      return getResponse()
          .getRemoteExecutionStoreRequest()
          .getDigests()
          .get(payloadIndex)
          .digest
          .sizeBytes;
    }

    @Override
    public OutputStream getStreamForPayload(int index) {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      payloads.put(
          getResponse().getRemoteExecutionStoreRequest().getDigests().get(index).getDigest(),
          stream);
      return stream;
    }
  }

  public InMemoryRemoteExecutionHttpService() {
    storage = Maps.newHashMap();
  }

  public RemoteExecutionStorageService createRemoteExecutionStorageService() {
    HybridThriftOverHttpService<FrontendRequest, FrontendResponse> httpService =
        new HybridThriftOverHttpServiceImpl<>(
            HybridThriftOverHttpServiceImplArgs.builder()
                .setService(this)
                .setExecutor(MoreExecutors.newDirectExecutorService())
                .build());
    return new RemoteExecutionStorageService(httpService);
  }

  @Override
  public HttpResponse makeRequest(String path, Builder requestBuilder) throws IOException {
    requestBuilder.url(FAKE_URL);
    ResponseHandler responseHandler = new ResponseHandler(new FrontendRequest());
    try (Buffer buffer = new Buffer()) {
      requestBuilder.build().body().writeTo(buffer);
      try (DataInputStream inStream = new DataInputStream(buffer.inputStream())) {
        HybridThriftOverHttpServiceImpl.readFromStream(inStream, PROTOCOL, responseHandler);
      }
    }

    switch (responseHandler.getResponse().getType()) {
      case REMOTE_EXECUTION_CONTAINS:
        return handleContains(responseHandler.getResponse());
      case REMOTE_EXECUTION_STORE:
        return handleStore(responseHandler);
      case REMOTE_EXECUTION_FETCH:
        return handleFetch(responseHandler.getResponse());
      default:
        throw new IllegalStateException();
    }
  }

  private HttpResponse handleFetch(FrontendRequest request) throws IOException {
    RemoteExecutionFetchResponse response = new RemoteExecutionFetchResponse();
    response.setDigests(
        request
            .getRemoteExecutionFetchRequest()
            .getDigests()
            .stream()
            .map(x -> new DigestAndContent().setDigest(x))
            .collect(Collectors.toList()));
    return createResponse(
        new FrontendResponse()
            .setType(request.getType())
            .setWasSuccessful(true)
            .setRemoteExecutionFetchResponse(response));
  }

  private HttpResponse handleStore(ResponseHandler responseHandler) throws IOException {
    storage.putAll(responseHandler.getPayloads());
    return createResponse(
        new FrontendResponse()
            .setType(responseHandler.getResponse().getType())
            .setWasSuccessful(true)
            .setRemoteExecutionStoreResponse(new RemoteExecutionStoreResponse()));
  }

  private HttpResponse handleContains(FrontendRequest frontendRequest) throws IOException {
    RemoteExecutionContainsResponse response = new RemoteExecutionContainsResponse();
    response.setContainedDigestsIsSet(true);
    response.setMissingDigestsIsSet(true);
    for (Digest digest : frontendRequest.getRemoteExecutionContainsRequest().getDigests()) {
      if (storage.containsKey(digest)) {
        response.addToContainedDigests(digest);
      } else {
        response.addToMissingDigests(digest);
      }
    }

    return createResponse(
        new FrontendResponse()
            .setType(frontendRequest.getType())
            .setWasSuccessful(true)
            .setRemoteExecutionContainsResponse(response));
  }

  private HttpResponse createResponse(FrontendResponse frontendResponse) throws IOException {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutStream = new DataOutputStream(outputStream)) {
      byte[] serialisedThrift = ThriftUtil.serialize(PROTOCOL, frontendResponse);
      HybridThriftOverHttpServiceImpl.writeToStream(
          dataOutStream,
          serialisedThrift,
          new HybridThriftRequestHandler<FrontendResponse>(frontendResponse) {
            @Override
            public long getTotalPayloadsSizeBytes() {
              if (frontendResponse.getType() != FrontendRequestType.REMOTE_EXECUTION_FETCH) {
                return 0;
              }

              long sizeBytes = 0;
              for (DigestAndContent digest :
                  frontendResponse.getRemoteExecutionFetchResponse().getDigests()) {
                sizeBytes += storage.get(digest).length;
              }

              return sizeBytes;
            }

            @Override
            public int getNumberOfPayloads() {
              if (frontendResponse.getType() != FrontendRequestType.REMOTE_EXECUTION_FETCH) {
                return 0;
              }

              return frontendResponse.getRemoteExecutionFetchResponse().getDigestsSize();
            }

            @Override
            public InputStream getPayloadStream(int index) {
              Digest digest =
                  frontendResponse
                      .getRemoteExecutionFetchResponse()
                      .getDigests()
                      .get(index)
                      .getDigest();
              return new ByteArrayInputStream(storage.get(digest));
            }
          });
      final byte[] responseBody = outputStream.toByteArray();
      return new HttpResponse() {
        @Override
        public int statusCode() {
          return 0;
        }

        @Override
        public String statusMessage() {
          return "";
        }

        @Override
        public long contentLength() {
          return responseBody.length;
        }

        @Override
        public InputStream getBody() {
          return new ByteArrayInputStream(responseBody);
        }

        @Override
        public String requestUrl() {
          return FAKE_URL;
        }

        @Override
        public void close() {}
      };
    }
  }

  @Override
  public void close() {}
}
