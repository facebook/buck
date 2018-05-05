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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import okhttp3.Request.Builder;

public class TestHttpService implements HttpService {

  private final Supplier<HttpResponse> httpResponseSupplier;
  private final AtomicInteger callsCount = new AtomicInteger();

  public TestHttpService(Supplier<HttpResponse> httpResponseSupplier) {
    this.httpResponseSupplier = httpResponseSupplier;
  }

  public TestHttpService() {
    this(null);
  }

  @Override
  public HttpResponse makeRequest(String path, Builder request) throws IOException {
    callsCount.incrementAndGet();
    return httpResponseSupplier.get();
  }

  @Override
  public void close() {}

  public int getCallsCount() {
    return callsCount.get();
  }
}
