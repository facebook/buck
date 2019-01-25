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

package com.facebook.buck.distributed;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.DigestAndContent;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.RemoteExecutionContainsRequest;
import com.facebook.buck.distributed.thrift.RemoteExecutionContainsResponse;
import com.facebook.buck.distributed.thrift.RemoteExecutionFetchRequest;
import com.facebook.buck.distributed.thrift.RemoteExecutionStoreRequest;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.slb.HybridThriftOverHttpService;
import com.facebook.buck.slb.HybridThriftRequestHandler;
import com.facebook.buck.slb.HybridThriftResponseHandler;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Provide Storage service for Remote Execution. */
public class RemoteExecutionStorageService implements AsyncBlobFetcher, CasBlobUploader {
  private static final Logger LOG = Logger.get(RemoteExecutionStorageService.class);

  private final HybridThriftOverHttpService<FrontendRequest, FrontendResponse> service;

  /** New instance. */
  public RemoteExecutionStorageService(
      HybridThriftOverHttpService<FrontendRequest, FrontendResponse> service) {
    this.service = service;
  }

  @Override
  public ListenableFuture<ByteBuffer> fetch(Digest digest) {
    try {
      try (ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
        return Futures.transform(
            fetchToStreamInternal(digest, outStream),
            future -> ByteBuffer.wrap(outStream.toByteArray()),
            MoreExecutors.directExecutor());
      }
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<Void> fetchToStream(Digest digest, OutputStream outputStream) {
    try {
      fetchToStreamInternal(digest, outputStream).get();
      return Futures.immediateFuture(null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw logAndThrowException(digest, e);
    } catch (ExecutionException e) {
      throw logAndThrowException(digest, e);
    }
  }

  private static BuckUncheckedExecutionException logAndThrowException(Digest digest, Exception e) {
    LOG.error(e, "Could not fetch stream for digest [%s].", digest);
    throw new BuckUncheckedExecutionException(
        e, "When fetching to stream for digest [%s].", digest);
  }

  private ListenableFuture<FrontendResponse> fetchToStreamInternal(
      Digest digest, OutputStream outputStream) {
    RemoteExecutionFetchRequest request = new RemoteExecutionFetchRequest();
    request.addToDigests(convertDigest(digest));
    FrontendRequest frontendRequest =
        new FrontendRequest()
            .setType(FrontendRequestType.REMOTE_EXECUTION_FETCH)
            .setRemoteExecutionFetchRequest(request);
    HybridThriftRequestHandler<FrontendRequest> hybridRequest =
        HybridThriftRequestHandler.createWithoutPayloads(frontendRequest);
    HybridThriftResponseHandler<FrontendResponse> hybridResponseHandler =
        createFetchResponseHandler(outputStream);
    return service.makeRequest(hybridRequest, hybridResponseHandler);
  }

  /** Creates a ResponseHandler for the RemoteExecutionFetch hybrid thrift request. */
  private static HybridThriftResponseHandler<FrontendResponse> createFetchResponseHandler(
      final OutputStream outputStream) {
    return new HybridThriftResponseHandler<FrontendResponse>(new FrontendResponse()) {
      @Override
      public void onResponseParsed() throws IOException {
        FrontendResponse response = getResponse();
        validateResponseOrThrow(response);
      }

      @Override
      public int getTotalPayloads() {
        int numberOfPayloads = getResponse().getRemoteExecutionFetchResponse().getDigestsSize();
        Preconditions.checkState(
            1 == numberOfPayloads,
            "Expected to only receive one payload but got [%d] instead.",
            numberOfPayloads);
        return numberOfPayloads;
      }

      @Override
      public long getPayloadSizeBytes(int payloadIndex) {
        DigestAndContent digestAndContent =
            getResponse().getRemoteExecutionFetchResponse().getDigests().get(payloadIndex);
        Preconditions.checkState(
            !digestAndContent.isSetContent(),
            "Unexpected inlined content from the server for digest [%s:%s].",
            digestAndContent.digest.hash,
            digestAndContent.digest.sizeBytes);

        return digestAndContent.getDigest().getSizeBytes();
      }

      @Override
      public OutputStream getStreamForPayload(int payloadIndex) {
        return outputStream;
      }
    };
  }

  /** Verifies if a server response was successful. */
  public static void validateResponseOrThrow(FrontendResponse response) throws ThriftException {
    if (!response.isSetWasSuccessful() || !response.isWasSuccessful()) {
      String msg =
          String.format(
              "RemoteExecution request of type [%s] failed with error message [%s].",
              response.getType(), response.getErrorMessage());
      LOG.error(msg);
      throw new ThriftException(msg);
    }
  }

  @Override
  public ImmutableSet<String> getMissingHashes(List<Digest> requiredDigests) throws IOException {
    RemoteExecutionContainsRequest request = new RemoteExecutionContainsRequest();
    request.setDigests(
        requiredDigests
            .stream()
            .map(RemoteExecutionStorageService::convertDigest)
            .collect(Collectors.toList()));

    FrontendRequest frontendRequest =
        new FrontendRequest()
            .setType(FrontendRequestType.REMOTE_EXECUTION_CONTAINS)
            .setRemoteExecutionContainsRequest(request);
    RemoteExecutionContainsResponse response = null;
    response =
        service
            .makeRequestSync(
                HybridThriftRequestHandler.createWithoutPayloads(frontendRequest),
                HybridThriftResponseHandler.createNoPayloadHandler(
                    new FrontendResponse(), RemoteExecutionStorageService::validateResponseOrThrow))
            .getRemoteExecutionContainsResponse();

    return response
        .getMissingDigests()
        .stream()
        .map(item -> item.getHash())
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableList<UploadResult> batchUpdateBlobs(final ImmutableList<UploadData> blobs) {
    RemoteExecutionStoreRequest request = new RemoteExecutionStoreRequest();
    final AtomicLong totalPayloadsSizeBytes = new AtomicLong(0);
    for (UploadData item : blobs) {
      com.facebook.buck.distributed.thrift.Digest digest = convertDigest(item.digest);
      new com.facebook.buck.distributed.thrift.Digest();
      totalPayloadsSizeBytes.addAndGet(item.digest.getSize());
      DigestAndContent digestAndContent = new DigestAndContent();
      digestAndContent.setDigest(digest);
      request.addToDigests(digestAndContent);
    }

    FrontendRequest frontendRequest =
        new FrontendRequest()
            .setType(FrontendRequestType.REMOTE_EXECUTION_STORE)
            .setRemoteExecutionStoreRequest(request);
    HybridThriftRequestHandler<FrontendRequest> hybridRequest =
        new HybridThriftRequestHandler<FrontendRequest>(frontendRequest) {
          @Override
          public long getTotalPayloadsSizeBytes() {
            return totalPayloadsSizeBytes.get();
          }

          @Override
          public int getNumberOfPayloads() {
            return blobs.size();
          }

          @Override
          public InputStream getPayloadStream(int index) throws IOException {
            return blobs.get(index).data.get();
          }
        };

    service.makeRequest(
        hybridRequest,
        HybridThriftResponseHandler.createNoPayloadHandler(
            new FrontendResponse(), RemoteExecutionStorageService::validateResponseOrThrow));

    return blobs
        .stream()
        .map(item -> new UploadResult(item.digest, 0, ""))
        .collect(ImmutableList.toImmutableList());
  }

  private static com.facebook.buck.distributed.thrift.Digest convertDigest(Digest digest) {
    return new com.facebook.buck.distributed.thrift.Digest()
        .setHash(digest.getHash())
        .setSizeBytes(digest.getSize());
  }
}
