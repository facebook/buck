/*
 * Copyright 2017-present Facebook, Inc.
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

import static com.facebook.buck.artifact_cache.thrift.ContainsResultType.CONTAINS;
import static com.facebook.buck.artifact_cache.thrift.ContainsResultType.DOES_NOT_CONTAIN;
import static com.facebook.buck.artifact_cache.thrift.ContainsResultType.UNKNOWN_DUE_TO_TRANSIENT_ERRORS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.artifact_cache.thrift.ArtifactMetadata;
import com.facebook.buck.artifact_cache.thrift.BuckCacheDeleteResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiContainsResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.ContainsResult;
import com.facebook.buck.artifact_cache.thrift.FetchResult;
import com.facebook.buck.artifact_cache.thrift.FetchResultType;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.artifact_cache.thrift.RuleKey;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.thrift.TBase;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ThriftArtifactCacheTest {
  @Rule public TemporaryPaths tempPaths = new TemporaryPaths();

  @Test
  public void testFetchResponseWithoutPayloadInfo() throws IOException, InterruptedException {
    ArtifactMetadata metadata = new ArtifactMetadata();
    metadata.addToRuleKeys(new RuleKey().setHashString("012345"));
    testWithMetadataAndPayloadInfo(metadata, false);
  }

  @Test
  public void testFetchResponseWithCorruptedRuleKeys() throws IOException, InterruptedException {
    ArtifactMetadata metadata = new ArtifactMetadata();
    metadata.addToRuleKeys(new RuleKey().setHashString("123\uFFFF"));
    testWithMetadata(metadata);
  }

  @Test
  public void testFetchResponseWithUnevenRuleKeyHash() throws IOException, InterruptedException {
    ArtifactMetadata metadata = new ArtifactMetadata();
    metadata.addToRuleKeys(new RuleKey().setHashString("akjdasadasdas"));
    testWithMetadata(metadata);
  }

  @Test
  public void testFetchResponseWithEmptyMetadata() throws IOException, InterruptedException {
    testWithMetadata(new ArtifactMetadata());
  }

  @Test
  public void testFetchResponseWithoutMetadata() throws IOException, InterruptedException {
    testWithMetadata(null);
  }

  private void testWithMetadata(@Nullable ArtifactMetadata artifactMetadata)
      throws IOException, InterruptedException {
    testWithMetadataAndPayloadInfo(artifactMetadata, true);
  }

  private void testWithMetadataAndPayloadInfo(
      @Nullable ArtifactMetadata artifactMetadata, boolean setPayloadInfo) throws IOException {
    HttpService storeClient = new TestHttpService();
    TestHttpService fetchClient =
        new TestHttpService(
            () -> makeResponseWithCorruptedRuleKeys(artifactMetadata, setPayloadInfo));
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempPaths.getRoot());
    ListeningExecutorService service = MoreExecutors.newDirectExecutorService();
    NetworkCacheArgs networkArgs =
        NetworkCacheArgs.builder()
            .setCacheName("default_cache_name")
            .setRepository("default_repository")
            .setCacheReadMode(CacheReadMode.READONLY)
            .setCacheMode(ArtifactCacheMode.thrift_over_http)
            .setScheduleType("default_schedule_type")
            .setProjectFilesystem(filesystem)
            .setFetchClient(fetchClient)
            .setStoreClient(storeClient)
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .setHttpWriteExecutorService(service)
            .setHttpFetchExecutorService(service)
            .setErrorTextTemplate("my super error msg")
            .setErrorTextLimit(100)
            .build();

    try (ThriftArtifactCache cache =
        new ThriftArtifactCache(
            networkArgs,
            "/nice_as_well",
            false,
            new BuildId("aabb"),
            0,
            0,
            "test://",
            "hostname")) {
      Path artifactPath = tempPaths.newFile().toAbsolutePath();
      CacheResult result =
          Futures.getUnchecked(
              cache.fetchAsync(
                  null,
                  new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(42)),
                  LazyPath.ofInstance(artifactPath)));
      assertEquals(CacheResultType.ERROR, result.getType());
    }

    assertEquals(1, fetchClient.getCallsCount());
  }

  private HttpResponse makeResponseWithCorruptedRuleKeys(
      @Nullable ArtifactMetadata artifactMetadata, boolean setPayloadInfo) {
    BuckCacheFetchResponse fetchResponse = new BuckCacheFetchResponse().setArtifactExists(true);
    if (artifactMetadata != null) {
      fetchResponse.setMetadata(artifactMetadata);
    }

    BuckCacheResponse response =
        new BuckCacheResponse()
            .setWasSuccessful(true)
            .setType(BuckCacheRequestType.FETCH)
            .setFetchResponse(fetchResponse);

    if (setPayloadInfo) {
      PayloadInfo payloadInfo = new PayloadInfo().setSizeBytes(0);
      response.addToPayloads(payloadInfo);
    }
    return new InMemoryThriftResponse(response);
  }

  private static class InMemoryThriftResponse implements HttpResponse {
    private byte[] response;

    public InMemoryThriftResponse(TBase<?, ?> thrift, byte[]... payloads) {
      byte[] serializedThrift = null;
      try {
        serializedThrift = ThriftUtil.serialize(ThriftArtifactCache.PROTOCOL, thrift);
      } catch (ThriftException e) {
        throw new RuntimeException(e);
      }

      ByteBuffer buffer =
          ByteBuffer.allocate(
              4
                  + serializedThrift.length
                  + Arrays.stream(payloads).mapToInt(p -> p.length).reduce(0, (l, r) -> l + r));
      buffer.order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(serializedThrift.length);
      buffer.put(serializedThrift);
      for (byte[] payload : payloads) {
        buffer.put(payload);
      }
      response = buffer.array();
    }

    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public String statusMessage() {
      return "";
    }

    @Override
    public long contentLength() {
      return response.length;
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(response);
    }

    @Override
    public String requestUrl() {
      return "";
    }

    @Override
    public void close() throws IOException {}
  }

  @Test
  public void testMultiFetch() throws IOException {
    AtomicReference<BuckCacheResponse> responseRef = new AtomicReference<>();
    AtomicReference<byte[]> payload1Ref = new AtomicReference<>();
    AtomicReference<byte[]> payload3Ref = new AtomicReference<>();
    HttpService storeClient = new TestHttpService();
    TestHttpService fetchClient =
        new TestHttpService(
            () ->
                new InMemoryThriftResponse(
                    responseRef.get(), payload1Ref.get(), payload3Ref.get()));
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempPaths.getRoot());
    ListeningExecutorService service = MoreExecutors.newDirectExecutorService();
    NetworkCacheArgs networkArgs =
        NetworkCacheArgs.builder()
            .setCacheName("default_cache_name")
            .setRepository("default_repository")
            .setCacheReadMode(CacheReadMode.READONLY)
            .setCacheMode(ArtifactCacheMode.thrift_over_http)
            .setScheduleType("default_schedule_type")
            .setProjectFilesystem(filesystem)
            .setFetchClient(fetchClient)
            .setStoreClient(storeClient)
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .setHttpWriteExecutorService(service)
            .setHttpFetchExecutorService(service)
            .setErrorTextTemplate("my super error msg")
            .setErrorTextLimit(100)
            .build();

    // 0 -> Miss, 1 -> Hit, 2 -> Skip, 3 -> Hit.
    Path output0 = filesystem.getPath("output0");
    Path output1 = filesystem.getPath("output1");
    Path output2 = filesystem.getPath("output2");
    Path output3 = filesystem.getPath("output3");

    SettableFuture<CacheResult> future = SettableFuture.create();

    com.facebook.buck.core.rulekey.RuleKey key0 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(0));
    com.facebook.buck.core.rulekey.RuleKey key1 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(1));
    com.facebook.buck.core.rulekey.RuleKey key2 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(2));
    com.facebook.buck.core.rulekey.RuleKey key3 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(3));
    ImmutableList<AbstractAsynchronousCache.FetchRequest> requests =
        ImmutableList.of(
            new AbstractAsynchronousCache.FetchRequest(
                null, key0, LazyPath.ofInstance(output0), future),
            new AbstractAsynchronousCache.FetchRequest(
                null, key1, LazyPath.ofInstance(output1), future),
            new AbstractAsynchronousCache.FetchRequest(
                null, key2, LazyPath.ofInstance(output2), future),
            new AbstractAsynchronousCache.FetchRequest(
                null, key3, LazyPath.ofInstance(output3), future));

    String payload1 = "payload1";
    String payload3 = "bigger payload3";
    byte[] payloadBytes1 = payload1.getBytes(Charsets.UTF_8);
    payload1Ref.set(payloadBytes1);
    byte[] payloadBytes3 = payload3.getBytes(Charsets.UTF_8);
    payload3Ref.set(payloadBytes3);

    ArtifactMetadata metadata1 = new ArtifactMetadata();
    metadata1.addToRuleKeys(new RuleKey().setHashString(key1.getHashCode().toString()));
    metadata1.setArtifactPayloadMd5(Hashing.md5().hashBytes(payloadBytes1).toString());
    metadata1.setMetadata(ImmutableMap.of());
    ArtifactMetadata metadata3 = new ArtifactMetadata();
    metadata3.addToRuleKeys(new RuleKey().setHashString(key3.getHashCode().toString()));
    metadata3.setArtifactPayloadMd5(Hashing.md5().hashBytes(payloadBytes3).toString());
    metadata3.setMetadata(ImmutableMap.of());

    BuckCacheMultiFetchResponse multiFetchResponse = new BuckCacheMultiFetchResponse();
    FetchResult result0 = new FetchResult().setResultType(FetchResultType.MISS);
    FetchResult result1 =
        new FetchResult().setResultType(FetchResultType.HIT).setMetadata(metadata1);
    FetchResult result2 = new FetchResult().setResultType(FetchResultType.SKIPPED);
    FetchResult result3 =
        new FetchResult().setResultType(FetchResultType.HIT).setMetadata(metadata3);

    multiFetchResponse.addToResults(result0);
    multiFetchResponse.addToResults(result1);
    multiFetchResponse.addToResults(result2);
    multiFetchResponse.addToResults(result3);

    PayloadInfo payloadInfo1 = new PayloadInfo().setSizeBytes(payloadBytes1.length);
    PayloadInfo payloadInfo3 = new PayloadInfo().setSizeBytes(payloadBytes3.length);

    BuckCacheResponse response =
        new BuckCacheResponse()
            .setWasSuccessful(true)
            .setType(BuckCacheRequestType.MULTI_FETCH)
            .setMultiFetchResponse(multiFetchResponse)
            .setPayloads(ImmutableList.of(payloadInfo1, payloadInfo3));
    responseRef.set(response);

    try (ThriftArtifactCache cache =
        new ThriftArtifactCache(
            networkArgs,
            "/nice_as_well",
            false,
            new BuildId("aabb"),
            0,
            0,
            "test://",
            "hostname")) {
      MultiFetchResult result = cache.multiFetchImpl(requests);
      assertEquals(4, result.getResults().size());
      assertEquals(CacheResultType.MISS, result.getResults().get(0).getCacheResult().getType());
      assertEquals(
          String.format("%s", result.getResults().get(1)),
          CacheResultType.HIT,
          result.getResults().get(1).getCacheResult().getType());
      assertEquals(CacheResultType.SKIPPED, result.getResults().get(2).getCacheResult().getType());
      assertEquals(CacheResultType.HIT, result.getResults().get(3).getCacheResult().getType());

      assertFalse(filesystem.exists(output0));
      assertEquals(payload1, filesystem.readFileIfItExists(output1).get());
      assertFalse(filesystem.exists(output2));
      assertEquals(payload3, filesystem.readFileIfItExists(output3).get());
    }

    assertEquals(1, fetchClient.getCallsCount());
  }

  @Test
  public void testMultiContains() throws IOException {
    AtomicReference<BuckCacheResponse> responseRef = new AtomicReference<>();
    HttpService storeClient = new TestHttpService();
    TestHttpService fetchClient =
        new TestHttpService(() -> new InMemoryThriftResponse(responseRef.get()));
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempPaths.getRoot());
    ListeningExecutorService service = MoreExecutors.newDirectExecutorService();
    NetworkCacheArgs networkArgs =
        NetworkCacheArgs.builder()
            .setCacheName("default_cache_name")
            .setRepository("default_repository")
            .setCacheReadMode(CacheReadMode.READONLY)
            .setCacheMode(ArtifactCacheMode.thrift_over_http)
            .setScheduleType("default_schedule_type")
            .setProjectFilesystem(filesystem)
            .setFetchClient(fetchClient)
            .setStoreClient(storeClient)
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .setHttpWriteExecutorService(service)
            .setHttpFetchExecutorService(service)
            .setErrorTextTemplate("my super error msg")
            .setErrorTextLimit(100)
            .build();

    com.facebook.buck.core.rulekey.RuleKey key0 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(0));
    com.facebook.buck.core.rulekey.RuleKey key1 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(1));
    com.facebook.buck.core.rulekey.RuleKey key2 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(2));
    com.facebook.buck.core.rulekey.RuleKey key3 =
        new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(3));
    ImmutableSet<com.facebook.buck.core.rulekey.RuleKey> ruleKeys =
        ImmutableSet.of(key0, key1, key2, key3);

    BuckCacheMultiContainsResponse multiContainsResponse = new BuckCacheMultiContainsResponse();
    ContainsResult result0 = new ContainsResult().setResultType(DOES_NOT_CONTAIN);
    ContainsResult result1 = new ContainsResult().setResultType(CONTAINS);
    ContainsResult result2 = new ContainsResult().setResultType(UNKNOWN_DUE_TO_TRANSIENT_ERRORS);
    ContainsResult result3 = new ContainsResult().setResultType(CONTAINS);

    multiContainsResponse.addToResults(result0);
    multiContainsResponse.addToResults(result1);
    multiContainsResponse.addToResults(result2);
    multiContainsResponse.addToResults(result3);

    BuckCacheResponse response =
        new BuckCacheResponse()
            .setWasSuccessful(true)
            .setType(BuckCacheRequestType.CONTAINS)
            .setMultiContainsResponse(multiContainsResponse);
    responseRef.set(response);

    try (ThriftArtifactCache cache =
        new ThriftArtifactCache(
            networkArgs,
            "/nice_as_well",
            false,
            new BuildId("aabb"),
            1,
            1,
            "test://",
            "hostname")) {
      MultiContainsResult result = cache.multiContainsImpl(ruleKeys);
      assertEquals(4, result.getCacheResults().size());
      assertEquals(CacheResultType.MISS, result.getCacheResults().get(key0).getType());
      assertEquals(CacheResultType.CONTAINS, result.getCacheResults().get(key1).getType());
      assertEquals(CacheResultType.ERROR, result.getCacheResults().get(key2).getType());
      assertEquals(CacheResultType.CONTAINS, result.getCacheResults().get(key3).getType());
    }

    assertEquals(1, fetchClient.getCallsCount());
  }

  private HttpResponse makeSuccessfulDeleteResponse() {
    BuckCacheDeleteResponse deleteResponse = new BuckCacheDeleteResponse();

    BuckCacheResponse response =
        new BuckCacheResponse()
            .setWasSuccessful(true)
            .setType(BuckCacheRequestType.DELETE_REQUEST)
            .setDeleteResponse(deleteResponse);

    return new InMemoryThriftResponse(response);
  }

  @Test
  public void testDelete() throws Exception {
    HttpService storeClient = new TestHttpService(this::makeSuccessfulDeleteResponse);
    TestHttpService fetchClient = new TestHttpService();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempPaths.getRoot());
    ListeningExecutorService service = MoreExecutors.newDirectExecutorService();
    NetworkCacheArgs networkArgs =
        NetworkCacheArgs.builder()
            .setCacheName("default_cache_name")
            .setRepository("default_repository")
            .setCacheReadMode(CacheReadMode.READWRITE)
            .setCacheMode(ArtifactCacheMode.thrift_over_http)
            .setScheduleType("default_schedule_type")
            .setProjectFilesystem(filesystem)
            .setFetchClient(fetchClient)
            .setStoreClient(storeClient)
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .setHttpWriteExecutorService(service)
            .setHttpFetchExecutorService(service)
            .setErrorTextTemplate("unused test error message")
            .setErrorTextLimit(100)
            .build();

    try (ThriftArtifactCache cache =
        new ThriftArtifactCache(
            networkArgs,
            "/nice_as_well",
            false,
            new BuildId("aabb"),
            0,
            0,
            "test://",
            "hostname")) {
      CacheDeleteResult result =
          Futures.getUnchecked(
              cache.deleteAsync(
                  Collections.singletonList(
                      new com.facebook.buck.core.rulekey.RuleKey(HashCode.fromInt(42)))));
      assertThat(result.getCacheNames(), Matchers.hasSize(1));
    }
  }
}
