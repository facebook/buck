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
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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

  private final OkHttpClient httpClient;
  private final URL endpointUrl;

  /**
   * @param endpointUrl the endpoint to upload to
   * @param timeout in milliseconds for http client
   */
  BuildReportUploader(URL endpointUrl, long timeout) {
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build();
    this.endpointUrl = endpointUrl;
  }

  /**
   * Upload a build report to the server
   *
   * @param report the report that's being uploaded
   * @return the endpoints' response
   * @throws IOException an exception related with the http connection or json mapping
   */
  public UploadResponse uploadReport(FullBuildReport report) throws IOException {
    String buildId = report.getBuildId().toString();

    report
        .versionControlStats()
        .flatMap(FullVersionControlStats::getDiff)
        .ifPresent(
            diffSupplier -> {
              try {
                InputStream inputStream = diffSupplier.get();
                uploadDiffFile(inputStream, buildId);
              } catch (VersionControlCommandFailedException | InterruptedException e) {
                LOG.error(e);
              } catch (IOException e) {
                LOG.warn(e, "Error when executing http call for diff file");
              }
            });

    return uploadReport(report, ImmutableMap.of("uuid", buildId));
  }

  private static RequestBody createRequestBody(
      ImmutableMap<String, String> extraArgs, String requestJson) {

    FormBody.Builder formBody = new FormBody.Builder();
    formBody.add("data", requestJson);

    extraArgs.forEach(formBody::add);

    return formBody.build();
  }

  @VisibleForTesting
  UploadResponse uploadReport(
      FullBuildReport report, ImmutableMap<String, String> extraRequestArguments)
      throws IOException {

    String encodedResponse;
    try {
      encodedResponse = ObjectMappers.WRITER.writeValueAsString(report);
    } catch (JsonProcessingException e) {
      throw new BuckUncheckedExecutionException(e, "Uploading build report");
    }

    RequestBody requestBody = createRequestBody(extraRequestArguments, encodedResponse);

    Request request = new Request.Builder().url(endpointUrl).post(requestBody).build();

    try {
      Response httpResponse = httpClient.newCall(request).execute();
      return handleResponse(httpResponse);
    } catch (IOException e) {
      LOG.error(e);
      throw e;
    }
  }

  static UploadResponse handleResponse(Response httpResponse) throws IOException {
    if (!httpResponse.isSuccessful()) {
      LOG.warn("Failed to upload build report, error: " + httpResponse.message());
      throw new BuckUncheckedExecutionException(
          "Failed to upload build report, response was unsuccessful");
    }

    ResponseBody responseBody = httpResponse.body();
    if (responseBody == null) {
      throw new BuckUncheckedExecutionException(
          "Failed to upload build report, response body was empty");
    }

    JsonNode root = ObjectMappers.READER.readTree(responseBody.string());
    if (root.has("error")) {
      String serverException = root.toString();
      LOG.warn("Build Report Endpoint error: " + serverException);
      throw new BuckUncheckedExecutionException("Build Report Endpoint error: " + serverException);
    }
    return ObjectMappers.READER.treeToValue(root, UploadResponse.class);
  }

  /**
   * @param inputStream that has an input stream containing the output of `hg export` command see
   *     {@link com.facebook.buck.util.versioncontrol.HgCmdLineInterface} for more info.
   * @param buildId of the current build
   * @return the endpoints' response
   * @throws IOException when an error occurs while executing the http call
   */
  public UploadResponse uploadDiffFile(InputStream inputStream, String buildId) throws IOException {

    String diffContent = CharStreams.toString(new InputStreamReader(inputStream));

    HttpUrl url = HttpUrl.get(endpointUrl);
    if (url == null) {
      throw new IllegalStateException("endpoint url should be a valid url");
    }

    Request request =
        new Request.Builder()
            .url(
                url.newBuilder()
                    .addQueryParameter("uuid", buildId)
                    .addQueryParameter("trace_file_kind", "diff_file")
                    .build())
            .post(
                new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "trace_file",
                        "hg_diff_" + buildId,
                        RequestBody.create(MediaType.parse("text/plain"), diffContent))
                    .build())
            .build();

    Response httpResponse = httpClient.newCall(request).execute();
    return handleResponse(httpResponse);
  }
}
