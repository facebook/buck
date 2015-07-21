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

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableCollection;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BatchingLoggerTest {

  private static class TestBatchingLogger extends BatchingLogger {
    private List<ImmutableCollection<BatchEntry>> uploadedBatches = new ArrayList<>();

    public TestBatchingLogger(int minBatchSize) {
      super(minBatchSize);
    }

    public List<ImmutableCollection<BatchEntry>> getUploadedBatches() {
      return uploadedBatches;
    }

    @Override
    protected ListenableFuture<Void> logMultiple(ImmutableCollection<BatchEntry> data) {
      uploadedBatches.add(data);
      return Futures.immediateFuture(null);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBatchingLogger() {
    String shortData = "data";
    String longData = "datdatdatdatdatdatdataaaaaaadata";
    BatchingLogger.BatchEntry shortDataBatch = new BatchingLogger.BatchEntry(shortData);
    BatchingLogger.BatchEntry longDataBatch = new BatchingLogger.BatchEntry(longData);
    TestBatchingLogger testBatchingLogger = new TestBatchingLogger(longData.length() + 1);

    testBatchingLogger.log(longData);
    // Data should still be buffered at this point.
    assertThat(testBatchingLogger.getUploadedBatches(), Matchers.hasSize(0));

    // This one should tip it over.
    testBatchingLogger.log(shortData);
    assertThat(testBatchingLogger.getUploadedBatches(), Matchers.hasSize(1));

    testBatchingLogger.log(shortData);
    testBatchingLogger.log(shortData);
    assertThat(testBatchingLogger.getUploadedBatches(), Matchers.hasSize(1));
    testBatchingLogger.close();
    assertThat(testBatchingLogger.getUploadedBatches(), Matchers.hasSize(2));

    assertThat(
        testBatchingLogger.getUploadedBatches(),
        Matchers.contains(
            Matchers.contains(longDataBatch, shortDataBatch),
            Matchers.contains(shortDataBatch, shortDataBatch)
        ));
  }
}
