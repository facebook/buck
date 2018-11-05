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

package com.facebook.buck.slb;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class LoadBalancedService implements HttpService {
  private static final Logger LOG = Logger.get(LoadBalancedService.class);

  private final HttpLoadBalancer slb;
  private final OkHttpClient client;
  private final BuckEventBus eventBus;

  public LoadBalancedService(HttpLoadBalancer slb, OkHttpClient client, BuckEventBus eventBus) {
    this.slb = slb;
    this.client = client;
    this.eventBus = eventBus;
  }

  @Override
  public HttpResponse makeRequest(String path, Request.Builder requestBuilder) throws IOException {
    URI server = slb.getBestServer();
    long startRequestNanos = System.nanoTime();
    LoadBalancedServiceEventData.Builder data =
        LoadBalancedServiceEventData.builder().setServer(server);
    URL fullUrl = SingleUriService.getFullUrl(server, path);
    requestBuilder.url(fullUrl);
    Request request = requestBuilder.build();
    if (request.body() != null && request.body().contentLength() != -1) {
      data.setRequestSizeBytes(request.body().contentLength());
    }
    LOG.verbose("Making call to %s", fullUrl);
    Call call = client.newCall(request);
    try {
      HttpResponse response =
          LoadBalancedHttpResponse.createLoadBalancedResponse(server, slb, call);
      if (response.contentLength() != -1) {
        data.setResponseSizeBytes(response.contentLength());
      }
      return response;
    } catch (IOException e) {
      data.setException(e);
      throw new IOException(e);
    } finally {
      data.setLatencyMicros((System.nanoTime() - startRequestNanos) / 1000);
      eventBus.post(new LoadBalancedServiceEvent(data.build()));
    }
  }

  @Override
  public void close() {
    slb.close();
  }
}
