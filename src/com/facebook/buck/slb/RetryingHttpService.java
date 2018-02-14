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
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.exceptions.RetryingException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;

public class RetryingHttpService implements HttpService {
  private static final Logger LOG = Logger.get(RetryingHttpService.class);

  private static final int NO_RETRY_INTERVAL = -1;

  private final HttpService decoratedService;
  private final int maxNumberOfAttempts;
  private final long retryRequestIntervalMillis;

  private final IntegerCounter successAfterRetryCountCounter;
  private final IntegerCounter retryCountCounter;
  private final IntegerCounter failAfterAllRetriesCountCounter;

  public RetryingHttpService(
      BuckEventBus eventBus,
      HttpService decoratedService,
      String counterCategory,
      int maxNumberOfRetries) {
    this(eventBus, decoratedService, counterCategory, maxNumberOfRetries, NO_RETRY_INTERVAL);
  }

  // Currently when there's a cache miss, all the children nodes get immediately retried without
  // any backoffs. We will do the same here for this initial implementation (and also to avoid
  // adding extra latency during the retry policy).
  public RetryingHttpService(
      BuckEventBus eventBus,
      HttpService decoratedService,
      String counterCategory,
      int maxNumberOfRetries,
      long retryRequestIntervalMillis) {
    Preconditions.checkArgument(
        maxNumberOfRetries >= 0,
        "The max number of retries needs to be non-negative instead of: %s",
        maxNumberOfRetries);
    this.decoratedService = decoratedService;
    this.maxNumberOfAttempts = maxNumberOfRetries + 1;
    this.retryRequestIntervalMillis = retryRequestIntervalMillis;

    failAfterAllRetriesCountCounter =
        new IntegerCounter(counterCategory, "fail_after_all_retries_count", ImmutableMap.of());

    successAfterRetryCountCounter =
        new IntegerCounter(counterCategory, "success_after_retry_count", ImmutableMap.of());

    retryCountCounter = new IntegerCounter(counterCategory, "retry_count", ImmutableMap.of());

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
        LOG.warn(
            exception, "encountered an exception while connecting to the service for %s", path);
        allExceptions.add(exception);
      }

      if (retryRequestIntervalMillis != NO_RETRY_INTERVAL) {
        try {
          Thread.sleep(retryRequestIntervalMillis);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
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

  public static class RetryingHttpServiceException extends RetryingException {

    public RetryingHttpServiceException(List<IOException> allExceptions) {
      super(allExceptions);
    }
  }
}
