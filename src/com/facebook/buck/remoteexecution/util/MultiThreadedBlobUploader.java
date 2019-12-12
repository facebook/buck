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

package com.facebook.buck.remoteexecution.util;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.core.exceptions.ThrowableCauseIterable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.CasBlobUploader.UploadResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
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
  private static final Logger LOG = Logger.get(MultiThreadedBlobUploader.class);

  private final int missingCheckLimit;
  private final int uploadSizeLimit;

  private final ConcurrentHashMap<String, ListenableFuture<Unit>> pendingUploads =
      new ConcurrentHashMap<>();

  private final Set<String> containedHashes = Sets.newConcurrentHashSet();
  private final BlockingDeque<PendingUpload> waitingUploads = new LinkedBlockingDeque<>();

  private final BlockingQueue<PendingUpload> waitingMissingCheck = new LinkedBlockingQueue<>();

  private final ExecutorService uploadService;
  private final CasBlobUploader asyncBlobUploader;

  private static class PendingUpload {
    private final UploadDataSupplier uploadData;
    private final SettableFuture<Unit> future;

    PendingUpload(UploadDataSupplier uploadData, SettableFuture<Unit> future) {
      this.uploadData = uploadData;
      this.future = future;
    }

    String getHash() {
      return uploadData.getDigest().getHash();
    }

    int getSize() {
      return uploadData.getDigest().getSize();
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
  public ListenableFuture<Unit> addMissing(Stream<UploadDataSupplier> dataSupplier) {
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

  private ListenableFuture<Unit> enqueue(ImmutableList<UploadDataSupplier> dataSupplier) {
    Builder<ListenableFuture<Unit>> futures = ImmutableList.builder();
    for (UploadDataSupplier data : dataSupplier) {
      Digest digest = data.getDigest();
      SettableFuture<Unit> future = SettableFuture.create();
      ListenableFuture<Unit> pendingFuture = pendingUploads.putIfAbsent(digest.getHash(), future);
      if (pendingFuture == null) {
        pendingFuture = future;
        if (containsDigest(digest)) {
          future.set(null);
        } else {
          SettableFuture<Unit> innerFuture = SettableFuture.create();
          waitingMissingCheck.add(new PendingUpload(data, innerFuture));
          future.setFuture(
              Futures.transform(
                  innerFuture,
                  ignored -> {
                    addContainedHash(digest);
                    return null;
                  },
                  directExecutor()));
          Futures.addCallback(
              future,
              MoreFutures.finallyCallback(() -> pendingUploads.remove(digest.getHash())),
              directExecutor());
          uploadService.submit(this::processUploads);
        }
      }
      futures.add(pendingFuture);
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
      Set<Digest> requiredDigests =
          data.stream().map(entry -> entry.uploadData.getDigest()).collect(Collectors.toSet());
      if (requiredDigests.size() != data.size()) {
        LOG.warn(
            "Request size doesn't match pending process missing list. Request size: "
                + requiredDigests.size()
                + " Pending Process Missing List size: "
                + data.size());
      }
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
    while (!waitingUploads.isEmpty()) {
      PendingUpload data = waitingUploads.poll();
      if (data == null) {
        break;
      }

      if (size == 0 || data.getSize() + size < uploadSizeLimit) {
        dataBuilder.put(data.getHash(), data);
        size += data.getSize();
      } else {
        // This object is too large to fit in this batch.
        // Add it back to the beginning of the upload queue for the next batch.
        waitingUploads.addFirst(data);
        break;
      }
    }
    ImmutableMap<String, PendingUpload> data = dataBuilder.build();

    if (!data.isEmpty()) {
      try {
        LOG.debug(
            "Starting Uploading: "
                + data.size()
                + " requests, size: "
                + size
                + ". "
                + String.join(", ", data.keySet()));
        if (size > uploadSizeLimit) {
          // This should only happen when we're trying to upload a single large object
          Preconditions.checkState(data.size() == 1);
          PendingUpload largeDataUpload = data.entrySet().iterator().next().getValue();
          UploadResult uploadResult =
              asyncBlobUploader.uploadFromStream(largeDataUpload.uploadData);
          setPendingUploadResult(largeDataUpload, uploadResult);
        } else {
          ImmutableList<UploadDataSupplier> blobs =
              data.values().stream()
                  .map(e -> e.uploadData)
                  .collect(ImmutableList.toImmutableList());

          ImmutableList<UploadResult> results = asyncBlobUploader.batchUpdateBlobs(blobs);
          Preconditions.checkState(results.size() == blobs.size());
          results.forEach(
              result -> {
                PendingUpload pendingUpload =
                    Objects.requireNonNull(data.get(result.digest.getHash()));
                setPendingUploadResult(pendingUpload, result);
              });
          data.forEach((k, pending) -> pending.future.setException(new RuntimeException("idk")));
        }
        LOG.debug("Finished Uploading: " + data.size() + " requests, size: " + size);
      } catch (Exception e) {
        data.forEach((k, pending) -> pending.future.setException(e));
      }
    }

    if (!waitingMissingCheck.isEmpty() || !waitingUploads.isEmpty()) {
      uploadService.submit(this::processUploads);
    }
  }

  private void setPendingUploadResult(PendingUpload upload, UploadResult result) {
    if (result.status == Status.Code.OK.value()) {
      upload.future.set(null);
    } else {
      String description = upload.uploadData.describe();
      String msg =
          String.format(
              "Failed uploading with message: %s. When uploading blob: %s.",
              result.message, description);
      if (result.status == Status.Code.INVALID_ARGUMENT.value()) {
        upload.future.setException(new CorruptArtifactException(msg, description));
      } else {
        upload.future.setException(new IOException(msg));
      }
    }
  }

  /** An exception that indicates that an upload failed because the artifact was corrupted. */
  public static class CorruptArtifactException extends IOException {

    private final String description;

    public CorruptArtifactException(String msg, String description) {
      super(msg);
      this.description = description;
    }

    public String getDescription() {
      return description;
    }

    /**
     * Determines if any of the causes in the {@link ThrowableCauseIterable} are
     * CorruptArtifactException
     *
     * @param iterable
     * @return
     */
    public static boolean isCause(ThrowableCauseIterable iterable) {
      for (Throwable t : iterable) {
        if (t instanceof CorruptArtifactException) {
          return true;
        }
      }
      return false;
    }

    /**
     * Returns CorruptArtifactException throwable if any of the causes in the {@link
     * ThrowableCauseIterable} are CorruptArtifactException. Check {@link
     * CorruptArtifactException#isCause(ThrowableCauseIterable)} first
     *
     * @param iterable
     * @return
     */
    public static Optional<String> getDescription(ThrowableCauseIterable iterable) {
      for (Throwable t : iterable) {
        if (t instanceof CorruptArtifactException) {
          return Optional.of(((CorruptArtifactException) t).getDescription());
        }
      }
      return Optional.empty();
    }
  }
}
