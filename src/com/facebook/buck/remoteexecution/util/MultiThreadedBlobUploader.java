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

package com.facebook.buck.remoteexecution.util;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.CasBlobUploader.UploadResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A simple multi-threaded blob uploader for uploading inputs/outputs to the CAS.
 *
 * <p>Before uploading a file, the uploader will check if the CAS already contains it.
 *
 * <p>All upload requests get added to a queue for the "missing check". Work threads will pull up to
 * missingCheckLimit items off this queue and send a request to the CAS to find which it
 * does/doesn't contain. Any that are missing will be added to a queue to be uploaded. Work threads
 * will then pull those off and upload them. When the upload is finished, the future for that digest
 * will be fulfilled.
 */
public class MultiThreadedBlobUploader {
  private final int missingCheckLimit;
  private final int uploadSizeLimit;

  private final ConcurrentHashMap<String, ListenableFuture<Void>> pendingUploads =
      new ConcurrentHashMap<>();

  private final Set<String> containedHashes = Sets.newConcurrentHashSet();
  private final BlockingQueue<PendingUpload> waitingUploads = new LinkedBlockingQueue<>();
  private final BlockingQueue<PendingUpload> waitingMissingCheck = new LinkedBlockingQueue<>();

  private final ExecutorService uploadService;
  private final CasBlobUploader asyncBlobUploader;

  private static class PendingUpload {
    private final UploadDataSupplier uploadData;
    private final SettableFuture<Void> future;

    PendingUpload(UploadDataSupplier uploadData, SettableFuture<Void> future) {
      this.uploadData = uploadData;
      this.future = future;
    }

    String getHash() {
      return uploadData.getDigest().getHash();
    }
  }

  public MultiThreadedBlobUploader(
      int missingCheckLimit,
      int uploadSizeLimit,
      ExecutorService uploadService,
      CasBlobUploader delegate) {
    this.missingCheckLimit = missingCheckLimit;
    this.uploadSizeLimit = uploadSizeLimit;
    this.uploadService = uploadService;
    this.asyncBlobUploader = delegate;
  }

  public boolean containsDigest(Digest digest) {
    return containedHashes.contains(digest.getHash());
  }

  private void addContainedHash(Digest digest) {
    containedHashes.add(digest.getHash());
  }

  /** Uploads missing items to the CAS. */
  public ListenableFuture<Void> addMissing(Stream<UploadDataSupplier> dataSupplier)
      throws IOException {
    ImmutableList<UploadDataSupplier> data =
        dataSupplier
            // We don't trust the caller to have applied filtering. This means that
            // each thing we upload we check this twice, but it is much, much more important to
            // optimize the already contained case.
            .filter(k -> !containsDigest(k.getDigest()))
            .collect(ImmutableList.toImmutableList());
    if (data.isEmpty()) {
      return Futures.immediateFuture(null);
    }
    return enqueue(data);
  }

  private ListenableFuture<Void> enqueue(ImmutableList<UploadDataSupplier> dataSupplier) {
    Builder<ListenableFuture<Void>> futures = ImmutableList.builder();
    for (UploadDataSupplier data : dataSupplier) {
      Digest digest = data.getDigest();
      ListenableFuture<Void> resultFuture =
          pendingUploads.computeIfAbsent(
              digest.getHash(),
              ignored -> {
                if (containsDigest(digest)) {
                  return Futures.immediateFuture(null);
                }
                SettableFuture<Void> future = SettableFuture.create();
                waitingMissingCheck.add(new PendingUpload(data, future));
                return Futures.transform(
                    future,
                    ignore -> {
                      // If the upload was successful short circuit for future requests.
                      addContainedHash(digest);
                      return null;
                    },
                    directExecutor());
              });
      Futures.addCallback(
          resultFuture,
          MoreFutures.finallyCallback(
              () -> {
                pendingUploads.remove(digest.getHash());
              }),
          directExecutor());
      futures.add(resultFuture);
      uploadService.submit(this::processUploads);
    }
    return Futures.whenAllSucceed(futures.build()).call(() -> null, directExecutor());
  }

  private void processMissing() {
    ImmutableList.Builder<PendingUpload> dataBuilder = ImmutableList.builder();
    int count = 0;
    while (count < missingCheckLimit && !waitingMissingCheck.isEmpty()) {
      PendingUpload data = waitingMissingCheck.poll();
      if (data == null) {
        break;
      }
      dataBuilder.add(data);
      count++;
    }

    if (count == 0) {
      return;
    }

    ImmutableList<PendingUpload> data = dataBuilder.build();

    try {
      List<Digest> requiredDigests =
          data.stream().map(entry -> entry.uploadData.getDigest()).collect(Collectors.toList());

      Set<String> missing = asyncBlobUploader.getMissingHashes(requiredDigests);

      for (PendingUpload entry : data) {
        if (missing.contains(entry.getHash())) {
          waitingUploads.add(entry);
        } else {
          entry.future.set(null);
        }
      }
    } catch (Throwable e) {
      data.forEach(d -> d.future.setException(e));
    }
  }

  private void processUploads() {
    processMissing();
    ImmutableMap.Builder<String, PendingUpload> dataBuilder = ImmutableMap.builder();
    int size = 0;
    while (size < uploadSizeLimit && !waitingUploads.isEmpty()) {
      PendingUpload data = waitingUploads.poll();
      if (data == null) {
        break;
      }
      dataBuilder.put(data.getHash(), data);
      size += data.uploadData.getDigest().getSize();
    }
    ImmutableMap<String, PendingUpload> data = dataBuilder.build();

    if (!data.isEmpty()) {
      try {
        ImmutableList.Builder<UploadDataSupplier> blobsBuilder = ImmutableList.builder();
        for (PendingUpload entry : data.values()) {
          blobsBuilder.add(entry.uploadData);
        }

        ImmutableList<UploadDataSupplier> blobs =
            data.values().stream().map(e -> e.uploadData).collect(ImmutableList.toImmutableList());

        ImmutableList<UploadResult> results = asyncBlobUploader.batchUpdateBlobs(blobs);
        Preconditions.checkState(results.size() == blobs.size());
        results.forEach(
            result -> {
              PendingUpload pendingUpload =
                  Objects.requireNonNull(data.get(result.digest.getHash()));
              if (result.status == 0) {
                pendingUpload.future.set(null);
              } else {
                pendingUpload.future.setException(
                    new IOException(
                        String.format(
                            "Failed uploading with message: %s. When uploading blob: %s.",
                            result.message, pendingUpload.uploadData.describe())));
              }
            });
        data.forEach((k, pending) -> pending.future.setException(new RuntimeException("idk")));
      } catch (Exception e) {
        data.forEach((k, pending) -> pending.future.setException(e));
      }
    }
    if (!waitingMissingCheck.isEmpty() || !waitingUploads.isEmpty()) {
      uploadService.submit(this::processUploads);
    }
  }
}
