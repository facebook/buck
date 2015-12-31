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

import com.google.common.base.Preconditions;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.net.URI;

public class SingleUriService implements HttpService {
  private final URI server;
  private final OkHttpClient client;

  public SingleUriService(URI server, OkHttpClient client) {
    this.server = server;
    this.client = client;
  }

  @Override
  public Response makeRequest(String path, Request.Builder requestBuilder) throws IOException {
    Preconditions.checkArgument(
        path.startsWith("/"),
        "Request URI must only be a path started with / instead of [%s].",
        path);
    requestBuilder.url(server.resolve(path).toURL());
    return client.newCall(requestBuilder.build()).execute();
  }

  @Override
  public void close() {
    // Nothing to close.
  }
}
