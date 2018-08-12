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

package com.facebook.buck.rules.modern.builders.thrift.cas;

import com.facebook.buck.rules.modern.builders.CasBlobUploader;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadData;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader.UploadResult;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol.ThriftDigest;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsRequest;
import com.facebook.remoteexecution.cas.BatchUpdateBlobsResponse;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.facebook.remoteexecution.cas.Digest;
import com.facebook.remoteexecution.cas.FindMissingBlobsRequest;
import com.facebook.remoteexecution.cas.UpdateBlobRequest;
import com.facebook.remoteexecution.cas.UpdateBlobResponse;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.thrift.TException;

/** A Thrift-based implementation of uploading inputs/outputs to the CAS. */
public class ThriftCasBlobUploader implements CasBlobUploader {

  private final ContentAddressableStorage.Client client;

  public ThriftCasBlobUploader(ContentAddressableStorage.Client client) {
    this.client = client;
  }

  @Override
  public ImmutableSet<String> getMissingHashes(List<Protocol.Digest> requiredDigests)
      throws IOException {
    List<Digest> digests =
        requiredDigests.stream().map(ThriftProtocol::get).collect(Collectors.toList());

    FindMissingBlobsRequest request = new FindMissingBlobsRequest(digests);
    try {
      return client
          .findMissingBlobs(request)
          .missing_blob_digests
          .stream()
          .map(Digest::getHash)
          .collect(ImmutableSet.toImmutableSet());
    } catch (TException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      e.printStackTrace();
      throw new BuckUncheckedExecutionException(e);
    }
  }

  @Override
  public ImmutableList<UploadResult> batchUpdateBlobs(ImmutableList<UploadData> blobs)
      throws IOException {
    try {
      List<UpdateBlobRequest> individualBlobRequests = new LinkedList<>();

      for (UploadData blob : blobs) {
        try (InputStream dataStream = blob.data.get()) {
          UpdateBlobRequest individualRequest =
              new UpdateBlobRequest(
                  ThriftProtocol.get(blob.digest),
                  ByteBuffer.wrap(ByteStreams.toByteArray(dataStream)));
          individualBlobRequests.add(individualRequest);
        }
      }

      BatchUpdateBlobsRequest request = new BatchUpdateBlobsRequest(individualBlobRequests);
      BatchUpdateBlobsResponse response = client.batchUpdateBlobs(request);
      ImmutableList.Builder<UploadResult> resultBuilder = ImmutableList.builder();

      for (UpdateBlobResponse individualResponse : response.getResponses()) {
        resultBuilder.add(
            new UploadResult(
                new ThriftDigest(individualResponse.getBlob_digest()),
                0, // status code; TODO(orr): We don't have that codified in our API yet. TBD
                "OK" // status message; TODO(orr): We don't have that codified in our API yet. TBD
                ));
      }
      return resultBuilder.build();
    } catch (TException e) {
      MoreThrowables.throwIfInitialCauseInstanceOf(e, IOException.class);
      e.printStackTrace();
      throw new BuckUncheckedExecutionException(e);
    }
  }
}
