/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.slb;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class SingleUriService implements HttpService {
  private static final String PATH_SEPARATOR = "/";

  private final URI server;
  private final OkHttpClient client;

  public SingleUriService(URI server, OkHttpClient client) {
    this.server = server;
    this.client = client;
  }

  @Override
  public HttpResponse makeRequest(String path, Request.Builder requestBuilder) throws IOException {
    requestBuilder.url(getFullUrl(server, path));
    return new OkHttpResponseWrapper(client.newCall(requestBuilder.build()).execute());
  }

  @Override
  public void close() {
    // Nothing to close.
  }

  public static URL getFullUrl(URI base, String extraPath) throws MalformedURLException {
    String baseUrl = base.toString();
    if (baseUrl.endsWith(PATH_SEPARATOR)) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    if (extraPath.startsWith(PATH_SEPARATOR)) {
      extraPath = extraPath.substring(1);
    }

    return new URL(baseUrl + PATH_SEPARATOR + extraPath);
  }
}
