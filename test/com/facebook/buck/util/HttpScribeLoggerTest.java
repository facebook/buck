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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class HttpScribeLoggerTest {

  @Test
  public void chunking() {
    final AtomicInteger requestCount = new AtomicInteger(0);
    HttpEndpoint httpEndpoint = new HttpEndpoint() {
      @Override
      public ListenableFuture<HttpResponse> post(String content) {
        requestCount.incrementAndGet();
        return Futures.immediateFuture(new HttpResponse(""));
      }
    };

    HttpScribeLogger logger = new HttpScribeLogger(httpEndpoint, 25);
    logger.log(
        "blah",
        ImmutableList.of(
            "hello world",
            "hello world",
            "hello world"));

    // Test that two of the log messages got batched together, while the third required an
    // additional request.
    assertEquals(2, requestCount.get());
  }

}
