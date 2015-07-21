/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.network;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Common functionality for uploading log entries in batches.
 */
public abstract class BatchingLogger implements RemoteLogger {

  public static final int DEFAULT_MIN_BATCH_SIZE = 1024 * 128; // This is pretty arbitrary.

  protected static class BatchEntry {
    private final String line;

    public BatchEntry(String line) {
      this.line = line;
    }

    public String getLine() {
      return line;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof BatchEntry)) {
        return false;
      }

      BatchEntry that = (BatchEntry) other;
      return line.equals(that.line);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(line);
    }

    @Override
    public String toString() {
      return String.format("BatchEntry(%s)", line);
    }
  }

  private ImmutableList.Builder<BatchEntry> batch;
  private int currentBatchSize;
  private final int minBatchSize;

  public BatchingLogger(int minBatchSize) {
    this.batch = ImmutableList.builder();
    this.currentBatchSize = 0;
    this.minBatchSize = minBatchSize;
  }

  public BatchingLogger() {
    this(DEFAULT_MIN_BATCH_SIZE);
  }

  @Override
  public final Optional<ListenableFuture<Void>> log(String jsonBlob) {
    batch.add(new BatchEntry(jsonBlob));
    currentBatchSize += jsonBlob.length();
    if (currentBatchSize >= minBatchSize) {
      return Optional.of(sendBatch());
    }
    return Optional.absent();
  }

  @Override
  public final ListenableFuture<Void> close() {
    return sendBatch();
  }

  private ListenableFuture<Void> sendBatch() {
    ImmutableList<BatchEntry> toSend = batch.build();
    batch = ImmutableList.builder();
    currentBatchSize = 0;
    if (toSend.isEmpty()) {
      return Futures.immediateFuture(null);
    } else {
      return logMultiple(toSend);
    }
  }

  protected abstract ListenableFuture<Void> logMultiple(ImmutableCollection<BatchEntry> data);
}
