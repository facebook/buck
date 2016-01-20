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

import com.facebook.buck.event.BuckEventBus;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.net.URI;

public class LoadBalancedService implements HttpService {
  private final HttpLoadBalancer slb;
  private final OkHttpClient client;
  private final BuckEventBus eventBus;

  public LoadBalancedService(HttpLoadBalancer slb, OkHttpClient client, BuckEventBus eventBus) {
    this.slb = slb;
    this.client = client;
    this.eventBus = eventBus;
  }

  @Override
  public Response makeRequest(
      String path, Request.Builder requestBuilder) throws IOException {
    URI server = slb.getBestServer();
    LoadBalancedServiceEventData.Builder data = LoadBalancedServiceEventData.builder()
        .setServer(server);
    requestBuilder.url(server.resolve(path).toURL());
    Request request = requestBuilder.build();
    if (request.body() != null && request.body().contentLength() != -1) {
      data.setRequestSizeBytes(request.body().contentLength());
    }
    Call call = client.newCall(request);
    try {
      Response response = call.execute();
      if (response.body() != null && response.body().contentLength() != -1) {
        data.setResponseSizeBytes(response.body().contentLength());
      }
      return response;
    } catch (IOException e) {
      data.setException(e);
      slb.reportException(server);
      throw new IOException(e);
    } finally {
      eventBus.post(new LoadBalancedServiceEvent(data.build()));
    }
  }

  @Override
  public void close() {
    slb.close();
  }
}
