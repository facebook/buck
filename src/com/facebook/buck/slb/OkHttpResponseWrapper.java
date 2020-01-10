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
import java.io.InputStream;
import okhttp3.Response;

public class OkHttpResponseWrapper implements HttpResponse {
  private final Response response;

  public OkHttpResponseWrapper(Response response) {
    this.response = response;
  }

  @Override
  public int statusCode() {
    return response.code();
  }

  @Override
  public String statusMessage() {
    return response.message();
  }

  @Override
  public long contentLength() throws IOException {
    return response.body().contentLength();
  }

  @Override
  public InputStream getBody() {
    return response.body().byteStream();
  }

  @Override
  public String requestUrl() {
    return response.request().url().toString();
  }

  @Override
  public void close() throws IOException {
    response.body().close();
  }

  protected Response getResponse() {
    return response;
  }
}
