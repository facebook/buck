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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Path;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/** A general way to upload any file to the build report endpoint */
public class BuildReportFileUploader {
  private static final Logger LOG = Logger.get(BuildReportFileUploader.class);

  private final RequestUploader requestUploader;

  public BuildReportFileUploader(URL url, long timeout, BuildId buildId) {
    requestUploader = new RequestUploader(url, timeout, buildId);
  }

  /**
   * @param buildReportFilePath is the file to upload
   * @param traceFileKind is the type of file to associate it correctly to the build in the server
   */
  public void uploadFile(Path buildReportFilePath, String traceFileKind) {

    try {
      RequestBody requestBody =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart(
                  "trace_file",
                  buildReportFilePath.getFileName().toString(),
                  RequestBody.create(MediaType.parse("text/plain"), buildReportFilePath.toFile()))
              .build();

      UploadResponse uploadResponse =
          requestUploader.uploadRequest(
              requestBody, ImmutableMap.of("trace_file_kind", traceFileKind));
      LOG.debug(
          "Successfully uploaded file: %s, response: %s",
          buildReportFilePath.getFileName().toString(), uploadResponse.toString());
    } catch (IOException e) {
      LOG.warn(e, "Error while uploading file: " + buildReportFilePath.getFileName().toString());
    }
  }

  /**
   * @param inputStream that has an input stream containing the output of `hg export` command see
   *     {@link com.facebook.buck.util.versioncontrol.HgCmdLineInterface} for more info.
   * @return the endpoints' response
   * @throws IOException when an error occurs while executing the http call
   */
  public UploadResponse uploadDiffFile(InputStream inputStream) throws IOException {

    String diffContent = CharStreams.toString(new InputStreamReader(inputStream));

    RequestBody requestBody =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "trace_file",
                this.requestUploader.getBuildId(),
                RequestBody.create(MediaType.parse("text/plain"), diffContent))
            .build();

    return requestUploader.uploadRequest(
        requestBody, ImmutableMap.of("trace_file_kind", "diff_file"));
  }
}
