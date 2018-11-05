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

package com.facebook.buck.manifestservice;

import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.artifact_cache.thrift.ManifestAppendRequest;
import com.facebook.buck.artifact_cache.thrift.ManifestDeleteRequest;
import com.facebook.buck.artifact_cache.thrift.ManifestFetchRequest;
import com.facebook.buck.artifact_cache.thrift.ManifestSetRequest;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.slb.HybridThriftOverHttpService;
import com.facebook.buck.slb.ThriftService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Implemention of the manifest service over a thrift service. */
public class ThriftManifestService implements ManifestService {
  private static final Logger LOG = Logger.get(ThriftManifestService.class);

  private final ThriftService<BuckCacheRequest, BuckCacheResponse> service;
  private final ListeningExecutorService executor;

  public ThriftManifestService(
      HybridThriftOverHttpService<BuckCacheRequest, BuckCacheResponse> service,
      ListeningExecutorService executor) {
    this.service = service;
    this.executor = executor;
  }

  @Override
  public ListenableFuture<Void> appendToManifest(Manifest manifest) {
    BuckCacheRequest request =
        new BuckCacheRequest()
            .setType(BuckCacheRequestType.MANIFEST_APPEND)
            .setManifestAppendRequest(new ManifestAppendRequest().setManifest(manifest));
    return executor.submit(
        () -> {
          makeRequestSync(request);
          return null;
        });
  }

  @Override
  public ListenableFuture<Manifest> fetchManifest(String manifestKey) {
    BuckCacheRequest request =
        new BuckCacheRequest()
            .setType(BuckCacheRequestType.MANIFEST_FETCH)
            .setManifestFetchRequest(new ManifestFetchRequest().setManifestKey(manifestKey));
    return executor.submit(() -> makeRequestSync(request).getManifestFetchResponse().getManifest());
  }

  @Override
  public ListenableFuture<Void> deleteManifest(String manifestKey) {
    BuckCacheRequest request =
        new BuckCacheRequest()
            .setType(BuckCacheRequestType.MANIFEST_DELETE)
            .setManifestDeleteRequest(new ManifestDeleteRequest().setManifestKey(manifestKey));
    return executor.submit(
        () -> {
          makeRequestSync(request);
          return null;
        });
  }

  @Override
  public ListenableFuture<Void> setManifest(Manifest manifest) {
    BuckCacheRequest request =
        new BuckCacheRequest()
            .setType(BuckCacheRequestType.MANIFEST_SET)
            .setManifestSetRequest(new ManifestSetRequest().setManifest(manifest));
    return executor.submit(
        () -> {
          makeRequestSync(request);
          return null;
        });
  }

  private BuckCacheResponse makeRequestSync(BuckCacheRequest request) throws IOException {
    BuckCacheResponse response = new BuckCacheResponse();
    service.makeRequest(request, response);

    if (!response.isWasSuccessful()) {
      String msg =
          String.format(
              "Request of type [%s] failed with error message [%s].",
              request.getType(), response.getErrorMessage());
      IOException exception = new IOException(msg);
      LOG.error(exception, msg);
      throw exception;
    }

    return response;
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      executor.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.error(e, "Failed to shutdown orderly the manifest service executor.");
      Thread.currentThread().interrupt();
    }
  }
}
