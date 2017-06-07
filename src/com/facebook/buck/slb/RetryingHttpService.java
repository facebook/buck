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
package com.facebook.buck.slb;

import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;

public class RetryingHttpService implements HttpService {

  public static final String COUNTER_CATEGORY = "buck_retry_service_counters";

  private final HttpService decoratedService;
  private final int maxNumberOfAttempts;

  private final IntegerCounter successAfterRetryCountCounter;
  private final IntegerCounter retryCountCounter;
  private final IntegerCounter failAfterAllRetriesCountCounter;

  // Currently when there's a cache miss, all the children nodes get immediately retried without
  // any backoffs. We will do the same here for this initial implementation (and also to avoid
  // adding extra latency during the retry policy).
  public RetryingHttpService(
      BuckEventBus eventBus, HttpService decoratedService, int maxNumberOfRetries) {
    Preconditions.checkArgument(
        maxNumberOfRetries >= 0,
        "The max number of retries needs to be non-negative instead of: %s",
        maxNumberOfRetries);
    this.decoratedService = decoratedService;
    this.maxNumberOfAttempts = maxNumberOfRetries + 1;

    failAfterAllRetriesCountCounter =
        new IntegerCounter(COUNTER_CATEGORY, "fail_after_all_retries_count", ImmutableMap.of());

    successAfterRetryCountCounter =
        new IntegerCounter(COUNTER_CATEGORY, "success_after_retry_count", ImmutableMap.of());

    retryCountCounter = new IntegerCounter(COUNTER_CATEGORY, "retry_count", ImmutableMap.of());

    eventBus.post(
        new CounterRegistry.AsyncCounterRegistrationEvent(
            ImmutableList.of(
                failAfterAllRetriesCountCounter,
                successAfterRetryCountCounter,
                retryCountCounter)));
  }

  @Override
  public HttpResponse makeRequest(String path, Request.Builder request) throws IOException {
    List<IOException> allExceptions = new ArrayList<>();
    for (int retryCount = 0; retryCount < maxNumberOfAttempts; retryCount++) {
      try {
        if (retryCount > 0) {
          retryCountCounter.inc();
        }

        HttpResponse response = decoratedService.makeRequest(path, request);

        if (retryCount > 0) {
          successAfterRetryCountCounter.inc();
        }
        return response;

      } catch (IOException exception) {
        allExceptions.add(exception);
      }
    }

    if (maxNumberOfAttempts > 0) {
      failAfterAllRetriesCountCounter.inc();
    }

    throw new RetryingHttpServiceException(allExceptions);
  }

  @Override
  public void close() {
    decoratedService.close();
  }

  public static class RetryingHttpServiceException extends IOException {
    public RetryingHttpServiceException(List<IOException> allExceptions) {
      super(generateMessage(allExceptions), allExceptions.get(allExceptions.size() - 1));
    }

    @Override
    public String toString() {
      return String.format("RetryingHttpServiceException{%s}", getMessage());
    }

    private static String generateMessage(List<IOException> exceptions) {
      StringBuilder builder = new StringBuilder();
      builder.append(
          String.format("Too many fails after %1$d retries. Exceptions:", exceptions.size()));
      for (int i = 0; i < exceptions.size(); ++i) {
        builder.append(String.format(" %d:[%s]", i, exceptions.get(i).toString()));
      }

      return builder.toString();
    }
  }
}
