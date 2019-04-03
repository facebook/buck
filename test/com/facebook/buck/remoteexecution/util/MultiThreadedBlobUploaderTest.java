/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.CasBlobUploader.UploadResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class MultiThreadedBlobUploaderTest {
  private static final GrpcProtocol PROTOCOL = new GrpcProtocol();

  private final int MISSING_CHECK_LIMIT = 1;
  private final int UPLOAD_SIZE_LIMT = 1;

  @Test
  public void testFailedFirstFindingMissingHashesAndSucceedingSecondTime()
      throws IOException, ExecutionException, InterruptedException {
    ExecutorService service = Executors.newSingleThreadExecutor();
    CasBlobUploader casBlobUploader = EasyMock.createMock(CasBlobUploader.class);
    ImmutableMap<Digest, UploadDataSupplier> data = createUploadData();
    Digest digest = data.keySet().asList().get(0);
    MultiThreadedBlobUploader uploader =
        new MultiThreadedBlobUploader(
            MISSING_CHECK_LIMIT, UPLOAD_SIZE_LIMT, service, casBlobUploader);

    // Setup EasyMock
    EasyMock.expect(casBlobUploader.getMissingHashes(Lists.newArrayList(digest)))
        .andThrow(new StatusRuntimeException(Status.INTERNAL))
        .once();

    EasyMock.expect(casBlobUploader.getMissingHashes(Lists.newArrayList(digest)))
        .andReturn(ImmutableSet.of(digest.getHash()))
        .once();

    UploadResult uploadResult = new UploadResult(digest, 0, "slicespin");
    EasyMock.expect(casBlobUploader.batchUpdateBlobs(EasyMock.anyObject()))
        .andReturn(ImmutableList.of(uploadResult))
        .once();
    EasyMock.replay(casBlobUploader);

    // Run the test case.
    ListenableFuture<Void> failedFuture = uploader.addMissing(data.values().stream());

    try {
      // Must throw.
      failedFuture.get();
      Assert.fail("Failed future must throw.");
    } catch (ExecutionException e) {
      Assert.assertEquals(StatusRuntimeException.class, e.getCause().getClass());
    }
    // Does not throw.
    ListenableFuture<Void> successfulFuture = uploader.addMissing(data.values().stream());
    successfulFuture.get();

    // Make sure all calls were exactly correctly.
    EasyMock.verify(casBlobUploader);
  }

  private ImmutableMap<Digest, UploadDataSupplier> createUploadData() {
    byte[] buffer = "topspin".getBytes();
    Digest digest = PROTOCOL.computeDigest(buffer);
    UploadDataSupplier supplier =
        new UploadDataSupplier() {
          @Override
          public InputStream get() throws IOException {
            return new ByteArrayInputStream(buffer);
          }

          @Override
          public Digest getDigest() {
            return digest;
          }
        };

    return ImmutableMap.of(digest, supplier);
  }
}
