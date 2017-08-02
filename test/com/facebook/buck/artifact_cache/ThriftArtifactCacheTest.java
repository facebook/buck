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

import com.facebook.buck.artifact_cache.thrift.ArtifactMetadata;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.artifact_cache.thrift.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.ThriftException;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.apache.thrift.TBase;
import org.easymock.EasyMock;
import org.junit.Assert;
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
      @Nullable ArtifactMetadata artifactMetadata, boolean setPayloadInfo)
      throws InterruptedException, IOException {
    HttpService storeClient = EasyMock.createNiceMock(HttpService.class);
    HttpService fetchClient = EasyMock.createMock(HttpService.class);
    BuckEventBus eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    ProjectFilesystem filesystem = new ProjectFilesystem(tempPaths.getRoot());
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
            .setBuckEventBus(eventBus)
            .setHttpWriteExecutorService(service)
            .setErrorTextTemplate("my super error msg")
            .setDistributedBuildModeEnabled(false)
            .setThriftEndpointPath("/nice_as_well")
            .build();

    EasyMock.expect(fetchClient.makeRequest(EasyMock.anyString(), EasyMock.anyObject()))
        .andReturn(makeResponseWithCorruptedRuleKeys(artifactMetadata, setPayloadInfo))
        .once();
    fetchClient.close();
    EasyMock.expectLastCall().once();
    EasyMock.replay(fetchClient);

    try (ThriftArtifactCache cache = new ThriftArtifactCache(networkArgs)) {
      Path artifactPath = tempPaths.newFile().toAbsolutePath();
      CacheResult result =
          Futures.getUnchecked(
              cache.fetchAsync(
                  new com.facebook.buck.rules.RuleKey(HashCode.fromInt(42)),
                  LazyPath.ofInstance(artifactPath)));
      Assert.assertEquals(CacheResultType.ERROR, result.getType());
    } catch (IOException e) {
      e.printStackTrace();
    }

    EasyMock.verify(fetchClient);
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

    public InMemoryThriftResponse(TBase<?, ?> payload) {
      byte[] serializedThrift = null;
      try {
        serializedThrift = ThriftUtil.serialize(ThriftArtifactCache.PROTOCOL, payload);
      } catch (ThriftException e) {
        throw new RuntimeException(e);
      }

      ByteBuffer buffer = ByteBuffer.allocate(4 + serializedThrift.length);
      buffer.order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(serializedThrift.length);
      buffer.put(serializedThrift);
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
    public long contentLength() throws IOException {
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
}
