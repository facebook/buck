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
import com.facebook.buck.artifact_cache.thrift.ManifestFetchResponse;
import com.facebook.buck.slb.HybridThriftOverHttpService;
import com.facebook.buck.slb.HybridThriftRequestHandler;
import com.facebook.buck.slb.HybridThriftResponseHandler;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ThriftManifestServiceTest {

  private static final long TIMEOUT_MILLIS = 1;

  private HybridThriftOverHttpServiceFake innerServiceFake;
  private ThriftManifestService service;

  @Before
  public void setUp() {
    innerServiceFake = new HybridThriftOverHttpServiceFake();
    service = new ThriftManifestService(innerServiceFake, MoreExecutors.newDirectExecutorService());
  }

  @Test
  public void testFetchManifest()
      throws InterruptedException, ExecutionException, TimeoutException {
    final Manifest manifest = createManifest();
    final AtomicReference<BuckCacheRequest> request = new AtomicReference<>();
    innerServiceFake.setCallback(
        (req, resp) -> {
          request.set(req);
          resp.setType(BuckCacheRequestType.MANIFEST_FETCH);
          resp.setWasSuccessful(true);
          ManifestFetchResponse manifestResponse = new ManifestFetchResponse();
          manifestResponse.setManifest(manifest);
          resp.setManifestFetchResponse(manifestResponse);
        });
    service.fetchManifest(manifest.getKey()).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    Assert.assertNotNull(request.get());
    Assert.assertEquals(BuckCacheRequestType.MANIFEST_FETCH, request.get().getType());
    Assert.assertEquals(
        manifest.getKey(), request.get().getManifestFetchRequest().getManifestKey());
  }

  @Test
  public void testAppendToManifest()
      throws InterruptedException, ExecutionException, TimeoutException {
    final Manifest manifest = createManifest();
    final AtomicReference<BuckCacheRequest> request = new AtomicReference<>();
    innerServiceFake.setCallback(
        (req, resp) -> {
          request.set(req);
          resp.setType(BuckCacheRequestType.MANIFEST_APPEND);
          resp.setWasSuccessful(true);
        });
    service.appendToManifest(manifest).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    Assert.assertNotNull(request.get());
    Assert.assertEquals(BuckCacheRequestType.MANIFEST_APPEND, request.get().getType());
    Assert.assertEquals(manifest, request.get().getManifestAppendRequest().getManifest());
  }

  @Test
  public void testDeleteManifest()
      throws InterruptedException, ExecutionException, TimeoutException {
    final Manifest manifest = createManifest();
    final AtomicReference<BuckCacheRequest> request = new AtomicReference<>();
    innerServiceFake.setCallback(
        (req, resp) -> {
          request.set(req);
          resp.setType(BuckCacheRequestType.MANIFEST_DELETE);
          resp.setWasSuccessful(true);
        });
    service.deleteManifest(manifest.getKey()).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    Assert.assertNotNull(request.get());
    Assert.assertEquals(BuckCacheRequestType.MANIFEST_DELETE, request.get().getType());
    Assert.assertEquals(
        manifest.getKey(), request.get().getManifestDeleteRequest().getManifestKey());
  }

  @Test
  public void testSetManifest() throws InterruptedException, ExecutionException, TimeoutException {
    final Manifest manifest = createManifest();
    final AtomicReference<BuckCacheRequest> request = new AtomicReference<>();
    innerServiceFake.setCallback(
        (req, resp) -> {
          request.set(req);
          resp.setType(BuckCacheRequestType.MANIFEST_SET);
          resp.setWasSuccessful(true);
        });
    service.setManifest(manifest).get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    Assert.assertNotNull(request.get());
    Assert.assertEquals(BuckCacheRequestType.MANIFEST_SET, request.get().getType());
    Assert.assertEquals(manifest, request.get().getManifestSetRequest().getManifest());
  }

  private static Manifest createManifest() {
    Manifest manifest = new Manifest();
    manifest.setKey("topspin_manifest_key");
    manifest.addToValues(ByteBuffer.wrap("slicespin_manifest_value".getBytes()));
    return manifest;
  }

  private static class HybridThriftOverHttpServiceFake
      implements HybridThriftOverHttpService<BuckCacheRequest, BuckCacheResponse> {

    public interface RequestHandlerCallback {
      void makeRequest(BuckCacheRequest thriftRequest, BuckCacheResponse thriftResponse)
          throws IOException;
    }

    private RequestHandlerCallback callback;

    public HybridThriftOverHttpServiceFake() {
      this.callback =
          (request, response) -> {
            /* no op */
          };
    }

    public void setCallback(RequestHandlerCallback callback) {
      this.callback = callback;
    }

    @Override
    public ListenableFuture<BuckCacheResponse> makeRequest(
        HybridThriftRequestHandler<BuckCacheRequest> request,
        HybridThriftResponseHandler<BuckCacheResponse> responseHandler) {
      throw new AssertionError("Should not be called.");
    }

    @Override
    public BuckCacheResponse makeRequestSync(
        HybridThriftRequestHandler<BuckCacheRequest> request,
        HybridThriftResponseHandler<BuckCacheResponse> responseHandler) {
      throw new AssertionError("Should not be called.");
    }

    @Override
    public void makeRequest(BuckCacheRequest buckCacheRequest, BuckCacheResponse buckCacheResponse)
        throws IOException {
      callback.makeRequest(buckCacheRequest, buckCacheResponse);
    }

    @Override
    public void close() throws IOException {}
  }
}
