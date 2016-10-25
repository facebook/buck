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

package com.facebook.buck.rage;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * Takes care of actually writing out the report.
 */
public class DefaultDefectReporter implements DefectReporter {

  private static final String REPORT_FILE_NAME = "report.json";
  private static final String DIFF_FILE_NAME = "changes.diff";
  private static final int HTTP_SUCCESS_CODE = 200;

  private final ProjectFilesystem filesystem;
  private final ObjectMapper objectMapper;
  private final RageConfig rageConfig;
  private final BuckEventBus buckEventBus;
  private final Clock clock;

  public DefaultDefectReporter(
      ProjectFilesystem filesystem,
      ObjectMapper objectMapper,
      RageConfig rageConfig,
      BuckEventBus buckEventBus,
      Clock clock
  ) {
    this.filesystem = filesystem;
    this.objectMapper = objectMapper;
    this.rageConfig = rageConfig;
    this.buckEventBus = buckEventBus;
    this.clock = clock;
  }

  private void addFilesToArchive(
      CustomZipOutputStream out,
      ImmutableSet<Path> paths) throws IOException {
    for (Path logFile : paths) {
      Preconditions.checkArgument(!logFile.isAbsolute(), "Should be a relative Path.", logFile);

      // If the file is hidden(UNIX terms) save it as normal file.
      if (logFile.getFileName().toString().startsWith(".")) {
        out.putNextEntry(new CustomZipEntry(
            Paths.get(logFile.getFileName().toString().substring(1))));
      } else {
        out.putNextEntry(new CustomZipEntry(logFile));
      }

      try (InputStream input = filesystem.newFileInputStream(logFile)) {
        ByteStreams.copy(input, out);
      }
      out.closeEntry();
    }
  }

  private void addStringsAsFilesToArchive(
      CustomZipOutputStream out,
      ImmutableMap<String, String> files) throws IOException {
    for (Map.Entry<String, String> file : files.entrySet()) {
      out.putNextEntry(new CustomZipEntry(file.getKey()));
      out.write(file.getValue().getBytes(Charsets.UTF_8));
      out.closeEntry();
    }
  }

  @Override
  public DefectSubmitResult submitReport(DefectReport defectReport) throws IOException {
    DefectSubmitResult.Builder defectSubmitResult = DefectSubmitResult.builder();

    Optional<SlbBuckConfig>  frontendConfig = rageConfig.getFrontendConfig();
    if (frontendConfig.isPresent()) {
      Optional<ClientSideSlb> slb =
          frontendConfig.get().tryCreatingClientSideSlb(
              clock,
              buckEventBus,
              new CommandThreadFactory("RemoteLog.HttpLoadBalancer"));
      if (slb.isPresent()) {
        try {
          return uploadReport(defectReport, defectSubmitResult, slb.get());
        } catch (IOException e) {
          defectSubmitResult.setReportSubmitErrorMessage(e.getMessage());
          defectSubmitResult.setUploadSuccess(false);
        }
      }
    }

    filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
    Path defectReportPath = filesystem.createTempFile(
        filesystem.getBuckPaths().getBuckOut(),
        "defect_report",
        ".zip");
    try (OutputStream outputStream = filesystem.newFileOutputStream(defectReportPath)) {
      writeReport(defectReport, outputStream);
    }
    return defectSubmitResult
        .setReportSubmitLocation(defectReportPath.toString())
        .build();
}

  private void writeReport(
      DefectReport defectReport,
      OutputStream outputStream) throws IOException {
    try (BufferedOutputStream baseOut = new BufferedOutputStream(outputStream);
         CustomZipOutputStream out =
             ZipOutputStreams.newOutputStream(baseOut, APPEND_TO_ZIP)) {
      if (defectReport.getSourceControlInfo().isPresent() &&
          defectReport.getSourceControlInfo().get().getDiff().isPresent()) {
        addStringsAsFilesToArchive(
            out,
            ImmutableMap.of(
                DIFF_FILE_NAME,
                defectReport.getSourceControlInfo().get().getDiff().get()));
      }
      addFilesToArchive(out, defectReport.getIncludedPaths());

      out.putNextEntry(new CustomZipEntry(REPORT_FILE_NAME));
      objectMapper.writeValue(out, defectReport);
    }
  }

  private DefectSubmitResult uploadReport(
      final DefectReport defectReport,
      DefectSubmitResult.Builder defectSubmitResult,
      ClientSideSlb slb) throws IOException {
    long timeout = rageConfig.getHttpTimeout();
    OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.MILLISECONDS)
        .readTimeout(timeout, TimeUnit.MILLISECONDS)
        .writeTimeout(timeout, TimeUnit.MILLISECONDS)
        .build();
    HttpService httpService = new RetryingHttpService(buckEventBus,
        new LoadBalancedService(slb, httpClient, buckEventBus),
        rageConfig.getMaxUploadRetries());

    try {
      Request.Builder requestBuilder = new Request.Builder();
      requestBuilder.post(
          new RequestBody() {
            @Override
            public MediaType contentType() {
              return MediaType.parse("application/x-www-form-urlencoded");
            }

            @Override
            public void writeTo(BufferedSink bufferedSink) throws IOException {
              writeReport(defectReport, bufferedSink.outputStream());
            }
          });

      HttpResponse response = httpService.makeRequest(
          rageConfig.getReportUploadPath(),
          requestBuilder);
      String responseBody;
      try (InputStream inputStream = response.getBody()) {
        responseBody = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
      }

      if (response.code() == HTTP_SUCCESS_CODE) {
        return defectSubmitResult
            .setReportSubmitLocation(response.requestUrl())
            .setReportSubmitMessage(responseBody)
            .setUploadSuccess(true)
            .build();
      } else {
        throw new IOException(
            String.format(
                "Connection to %s returned code %d and message: %s",
                response.requestUrl(),
                response.code(),
                responseBody));
      }
    } catch (IOException e) {
      throw new IOException(String.format("Failed uploading report because [%s].", e.getMessage()));
    } finally {
      httpService.close();
    }
  }
}
