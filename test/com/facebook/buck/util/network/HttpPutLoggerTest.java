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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class HttpPutLoggerTest {

  @Test
  public void testHttpLogger() throws Exception {
    final ConcurrentLinkedQueue<String> uploadedData = new ConcurrentLinkedQueue<>();
    HttpEndpoint testEndpoint = new HttpEndpoint() {
      @Override
      public ListenableFuture<HttpResponse> post(String content) {
        uploadedData.add(content);
        return Futures.immediateFuture(new HttpResponse(""));
      }
    };
    HttpPutLogger httpPutLogger =
        new HttpPutLogger(testEndpoint, new ObjectMapper());

    String entry1 = "{\"e\":1}";
    String entry2 = "{\"e\":2}";

    httpPutLogger.log(entry1);
    httpPutLogger.log(entry2);
    httpPutLogger.close().get(0, TimeUnit.SECONDS);

    assertThat(
        uploadedData,
        Matchers.contains(
            "[" + entry1 + "," + entry2 + "]"
        )
    );
  }
}
