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

import com.facebook.buck.distributed.testutil.InMemoryRemoteExecutionHttpService;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadData;
import com.facebook.buck.rules.modern.builders.Protocol.Digest;
import com.facebook.buck.rules.modern.builders.ThriftProtocol;
import com.facebook.buck.slb.ThriftException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionStorageServiceTest {
  private InMemoryRemoteExecutionHttpService inMemoryService;
  private RemoteExecutionStorageService service;
  private ThriftProtocol protocol;

  @Before
  public void setUp() {
    inMemoryService = new InMemoryRemoteExecutionHttpService();
    service = inMemoryService.createRemoteExecutionStorageService();
    protocol = new ThriftProtocol();
  }

  @Test
  public void testResponseValidation() {
    FrontendResponse response = new FrontendResponse();
    response.setWasSuccessful(false);
    response.setErrorMessage("topspin");
    try {
      RemoteExecutionStorageService.validateResponseOrThrow(response);
      Assert.fail("Unsuccessful response should've thrown an exception.");
    } catch (ThriftException e) {
      Assert.assertTrue(e.getMessage().contains(response.getErrorMessage()));
    }
  }

  @Test
  public void testStoreAndFetchAfter()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    ImmutableList<UploadData> allUploadData = createData("key1", "key2");
    service.batchUpdateBlobs(allUploadData);
    for (UploadData data : allUploadData) {
      ByteBuffer buffer = service.fetch(data.digest).get();
      Assert.assertTrue(Arrays.equals(ByteStreams.toByteArray(data.data.get()), buffer.array()));
    }
  }

  @Test
  public void testStoreAndMissing()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    ImmutableList<UploadData> allUploadData = createData("key3", "key4");
    service.batchUpdateBlobs(allUploadData);

    List<Digest> digests = allUploadData.stream().map(x -> x.digest).collect(Collectors.toList());
    List<Digest> nonExistentDigests =
        Lists.newArrayList(protocol.newDigest("topspin", 1), protocol.newDigest("slicespin", 3));
    digests.addAll(nonExistentDigests);
    ImmutableSet<String> missingHashes = service.getMissingHashes(digests);
    Assert.assertEquals(missingHashes.size(), missingHashes.size());
    for (Digest missingDigest : nonExistentDigests) {
      Assert.assertTrue(missingHashes.contains(missingDigest.getHash()));
    }
  }

  private ImmutableList<UploadData> createData(String... hashes) {
    List<UploadData> allPayloads = Lists.newArrayList();
    for (String hash : hashes) {
      byte[] data = createRandomBytes();
      Digest digest = protocol.newDigest(hash, data.length);
      UploadData uploadData = new UploadData(digest, () -> new ByteArrayInputStream(data));
      allPayloads.add(uploadData);
    }

    return ImmutableList.copyOf(allPayloads);
  }

  private static byte[] createRandomBytes() {
    Random random = new Random();
    int sizeBytes = random.nextInt(100);
    byte[] buffer = new byte[sizeBytes];
    random.nextBytes(buffer);
    return buffer;
  }
}
