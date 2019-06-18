/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.support.build.report;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * BuildReportUploader does the heavy lifting of {@link BuildReportUpload}. It encodes and uploads
 * the given {@link FullBuildReport}
 */
public class BuildReportUploader {
  private static final Logger LOG = Logger.get(BuildReportUploader.class);

  /**
   * Upload a build report to the server
   *
   * @param report the report that's being uploaded
   * @param endpointUrl the endpoint to upload to
   * @param timeout in milliseconds for http client
   * @return the endpoints' response
   * @throws IOException an exception related with the http connection or json mapping
   */
  public static UploadResponse uploadReport(FullBuildReport report, URL endpointUrl, long timeout)
      throws IOException {

    OkHttpClient.Builder httpClientBuilder =
        new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS);

    return uploadReport(httpClientBuilder.build(), report, endpointUrl, ImmutableMap.of());
  }

  private static RequestBody createRequestBody(
      ImmutableMap<String, String> extraArgs, String requestJson) {

    FormBody.Builder formBody = new FormBody.Builder();
    formBody.add("data", requestJson);

    extraArgs.forEach(formBody::add);

    return formBody.build();
  }

  @VisibleForTesting
  static UploadResponse uploadReport(
      OkHttpClient httpClient,
      FullBuildReport report,
      URL endpointUrl,
      ImmutableMap<String, String> extraRequestArguments)
      throws IOException {

    String encodedResponse;
    try {
      encodedResponse = ObjectMappers.WRITER.writeValueAsString(report);
    } catch (JsonProcessingException e) {
      throw new BuckUncheckedExecutionException(e, "Uploading build report");
    }

    RequestBody requestBody = createRequestBody(extraRequestArguments, encodedResponse);

    Request.Builder requestBuilder =
        new Request.Builder().url(endpointUrl.toString()).post(requestBody);

    try {
      Response httpResponse = httpClient.newCall(requestBuilder.build()).execute();

      if (!httpResponse.isSuccessful()) {
        throw new BuckUncheckedExecutionException(
            "Failed to upload build report, response was unsuccessful");
      }

      ResponseBody responseBody = httpResponse.body();
      if (responseBody == null) {
        throw new BuckUncheckedExecutionException(
            "Failed to upload build report, response body was empty");
      }

      String body = responseBody.string();
      return ObjectMappers.readValue(body, UploadResponse.class);

    } catch (IOException e) {
      LOG.error(e);
      throw e;
    }
  }
}
