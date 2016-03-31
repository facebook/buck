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

import com.squareup.okhttp.Response;

import java.io.IOException;
import java.io.InputStream;

public class OkHttpResponseWrapper implements HttpResponse {
  private final Response response;

  public OkHttpResponseWrapper(Response response) {
    this.response = response;
  }

  @Override
  public int code() {
    return response.code();
  }

  @Override
  public long contentLength() throws IOException {
    return response.body().contentLength();
  }

  @Override
  public InputStream getBody() throws IOException {
    return response.body().byteStream();
  }

  @Override
  public String requestUrl() {
    return response.request().urlString();
  }

  @Override
  public void close() throws IOException {
    response.body().close();
  }

  protected Response getResponse() {
    return response;
  }
}
