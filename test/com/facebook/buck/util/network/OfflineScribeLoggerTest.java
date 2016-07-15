/*
 * Copyright 2016-present Facebook, Inc.
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



import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class OfflineScribeLoggerTest {

  @Test
  public void unsentLinesStoredForOffline() throws Exception {
    final String whitelistedCategory = "whitelisted_category";
    final String whitelistedCategory2 = "whitelisted_category_2";
    final String blacklistedCategory = "blacklisted_category";
    FakeFailingOfflineScribeLogger fakeLogger = new FakeFailingOfflineScribeLogger(
        ImmutableList.of(blacklistedCategory));


    // Simulate network issues occurring for some of sending attempts (all after first one).

    // Logging succeeds.
    fakeLogger.log(
        whitelistedCategory,
        ImmutableList.of(
            "hello world 1",
            "hello world 2"));
    // Logging fails.
    fakeLogger.log(
        whitelistedCategory,
        ImmutableList.of(
            "hello world 3",
            "hello world 4"));
    // Event with blacklisted (not whitelisted) category for offline logging.
    fakeLogger.log(
        blacklistedCategory,
        ImmutableList.of(
            "hello world 5",
            "hello world 6"));
    // Logging fails.
    fakeLogger.log(
        whitelistedCategory2,
        ImmutableList.of(
            "hello world 7",
            "hello world 8"));

    fakeLogger.close();

    assertEquals(2, fakeLogger.getStoredCategoriesWithLinesCount());
  }

  /**
   * Fake implementation of {@link OfflineScribeLogger} which fails to log after the first attempt
   * (which succeeds) and allows for checking count of stored categories with lines.
   */
  private final class FakeFailingOfflineScribeLogger extends ScribeLogger {

    private final OfflineScribeLogger offlineScribeLogger;
    private final ImmutableList<String> blacklistCategories;
    private final AtomicInteger storedCategoriesWithLines;

    public FakeFailingOfflineScribeLogger(ImmutableList<String> blacklistCategories) {
      this.offlineScribeLogger = new OfflineScribeLogger(
          new FakeFailingScribeLogger(),
          blacklistCategories);
      this.blacklistCategories = blacklistCategories;
      storedCategoriesWithLines = new AtomicInteger(0);
    }

    @Override
    public ListenableFuture<Void> log(final String category, final Iterable<String> lines) {
      ListenableFuture<Void> upload = offlineScribeLogger.log(category, lines);
      Futures.addCallback(
          upload,
          new FutureCallback<Void>() {
            @Override
            public void onSuccess(Void result){
            }

            @Override
            public void onFailure(Throwable t) {
              if (!blacklistCategories.contains(category)) {
                storedCategoriesWithLines.incrementAndGet();
              }
            }
          });
      return upload;
    }

    @Override
    public void close() throws Exception {
      offlineScribeLogger.close();
    }

    public int getStoredCategoriesWithLinesCount() {
      return storedCategoriesWithLines.get();
    }

  }
}
