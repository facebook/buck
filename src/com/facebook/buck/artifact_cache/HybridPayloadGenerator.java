/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.thrift.ArtifactMetadata;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheStoreRequest;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.artifact_cache.thrift.RuleKey;
import com.facebook.buck.slb.ThriftProtocol;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteSource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * * Creates example fetch and store requests, encodes them using the hybrid thrift/binary protocol,
 * and finally prints out the hybrid payload using base 64 encoding.
 *
 * <p>Used as input to tests in the cache server.
 */
public class HybridPayloadGenerator {
  private static final ThriftProtocol PROTOCOL = ThriftArtifactCache.PROTOCOL;
  private static final String PAYLOAD_ONE = "payloadOne";
  private static final byte[] PAYLOAD_ONE_BYTES; // See static {} for initialization

  private static final String STORE_RULE_KEY_ONE = "ruleKeyOne";
  private static final String STORE_RULE_KEY_TWO = "ruleKeyTwo";

  private static final String METDATA_KEY_ONE = "metaDataKeyOne";
  private static final String METDATA_KEY_TWO = "metaDataKeyTwo";

  private static final String METDATA_VALUE_ONE = "metaDataValueOne";
  private static final String METDATA_VALUE_TWO = "metaDataValueTwo";

  static {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (DataOutputStream dataOs = new DataOutputStream(bos)) {
      dataOs.writeUTF(PAYLOAD_ONE);
    } catch (IOException e) {
      e.printStackTrace();
    }
    PAYLOAD_ONE_BYTES = bos.toByteArray();
  }

  public static void main(String[] args) throws IOException {
    HybridPayloadGenerator encoder = new HybridPayloadGenerator();
    encoder.encodeHybridFetchRequestNoPayload();
    encoder.encodeHybridStoreRequestOnePayload();
  }

  public void encodeHybridFetchRequestNoPayload() throws IOException {
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, createFetchRequest());

    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      System.out.println("Fetch request:");
      System.out.println(BaseEncoding.base64().encode(buffer));
    }
  }

  public void encodeHybridStoreRequestOnePayload() throws IOException {
    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(
            PROTOCOL,
            createStoreRequest(),
            ByteSource.wrap(Arrays.copyOf(PAYLOAD_ONE_BYTES, PAYLOAD_ONE_BYTES.length)));

    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      request.writeAndClose(stream);
      stream.flush();
      byte[] buffer = stream.toByteArray();
      System.out.println("Store request:");
      System.out.println(BaseEncoding.base64().encode(buffer));
    }
  }

  private BuckCacheRequest createFetchRequest() {
    BuckCacheFetchRequest fetchRequest = new BuckCacheFetchRequest();
    RuleKey ruleKey = new RuleKey();
    ruleKey.setHashString(STORE_RULE_KEY_ONE);
    fetchRequest.setRuleKey(ruleKey);

    BuckCacheRequest cacheRequest = new BuckCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.FETCH);
    cacheRequest.setFetchRequest(fetchRequest);
    cacheRequest.setType(BuckCacheRequestType.FETCH);

    return cacheRequest;
  }

  private BuckCacheRequest createStoreRequest() {
    BuckCacheRequest cacheRequest = new BuckCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.STORE);
    BuckCacheStoreRequest storeRequest = new BuckCacheStoreRequest();

    ArtifactMetadata metadata = new ArtifactMetadata();

    RuleKey ruleKeyOne = new RuleKey();
    ruleKeyOne.setHashString(STORE_RULE_KEY_ONE);
    RuleKey ruleKeyTwo = new RuleKey();
    ruleKeyTwo.setHashString(STORE_RULE_KEY_TWO);
    List<RuleKey> ruleKeys = new ArrayList<>();
    ruleKeys.add(ruleKeyOne);
    ruleKeys.add(ruleKeyTwo);
    metadata.setRuleKeys(ruleKeys);

    Map<String, String> metadataMap = new HashMap<>();
    metadataMap.put(METDATA_KEY_ONE, METDATA_VALUE_ONE);
    metadataMap.put(METDATA_KEY_TWO, METDATA_VALUE_TWO);

    metadata.setRuleKeys(ruleKeys);
    metadata.setMetadata(metadataMap);

    List<PayloadInfo> payloadInfos = new ArrayList<>();
    PayloadInfo payloadInfo = new PayloadInfo();
    payloadInfo.setSizeBytes(PAYLOAD_ONE_BYTES.length);
    payloadInfos.add(payloadInfo);

    storeRequest.setMetadata(metadata);
    cacheRequest.setStoreRequest(storeRequest);
    cacheRequest.setPayloads(payloadInfos);

    return cacheRequest;
  }
}
