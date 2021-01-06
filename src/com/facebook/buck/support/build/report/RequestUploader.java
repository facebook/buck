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

package com.facebook.buck.support.build.report;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * RequestUploader is only concerned with uploading and handling a request of type {@code
 * RequestBody} related to a buildId.
 */
public class RequestUploader {
  private static final Logger LOG = Logger.get(RequestUploader.class);

  private final OkHttpClient httpClient;
  private final URL endpointUrl;
  private final String buildId;

  /**
   * @param endpointUrl the endpoint to upload to
   * @param timeout in milliseconds for http client
   */
  RequestUploader(URL endpointUrl, long timeout, BuildId buildId) {
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build();
    this.endpointUrl = endpointUrl;
    this.buildId = buildId.toString();
  }

  public String getBuildId() {
    return this.buildId;
  }

  static UploadResponse handleResponse(Response httpResponse) throws IOException {
    if (!httpResponse.isSuccessful()) {
      LOG.warn("Failed to upload request, error: " + httpResponse.message());
      throw new BuckUncheckedExecutionException(
          "Failed to upload request, response was unsuccessful");
    }

    ResponseBody responseBody = httpResponse.body();
    if (responseBody == null) {
      throw new BuckUncheckedExecutionException(
          "Failed to upload request, response body was empty");
    }

    JsonNode root = ObjectMappers.READER.readTree(responseBody.string());
    if (root.has("error")) {
      String serverException = root.toString();
      LOG.warn("Build Report Endpoint error: " + serverException);
      throw new BuckUncheckedExecutionException("Build Report Endpoint error: " + serverException);
    }
    return ObjectMappers.READER.treeToValue(root, UploadResponse.class);
  }

  public UploadResponse uploadRequest(RequestBody requestBody) throws IOException {
    return uploadRequest(requestBody, ImmutableMap.of());
  }

  /**
   * Uploads the request to the build report endpoint, adding extra {@code queryParams} to the url.
   *
   * @throws IOException in case of a http error
   */
  public UploadResponse uploadRequest(
      RequestBody requestBody, ImmutableMap<String, String> queryParams) throws IOException {
    HttpUrl url = HttpUrl.get(endpointUrl);
    if (url == null) {
      throw new IllegalStateException("endpoint url should be a valid url");
    }

    HttpUrl.Builder urlBuilder = url.newBuilder().addQueryParameter("uuid", buildId);
    queryParams.forEach(urlBuilder::addQueryParameter);

    Request request = new Request.Builder().url(urlBuilder.build()).post(requestBody).build();

    try (Response httpResponse = httpClient.newCall(request).execute()) {
      return handleResponse(httpResponse);
    }
  }
}
