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

package com.facebook.buck.doctor;

import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorJsonResponse;
import com.facebook.buck.doctor.config.DoctorProtocolVersion;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.slb.SlbBuckConfig;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.facebook.buck.util.versioncontrol.VersionControlSupplier;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
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

/** Takes care of actually writing out the report. */
public class DefaultDefectReporter implements DefectReporter {

  private static final Logger LOG = Logger.get(AbstractReport.class);

  private static final String REPORT_FILE_NAME = "report.json";
  private static final String DIFF_FILE_NAME = "changes.diff";
  private static final int HTTP_SUCCESS_CODE = 200;
  private static final String REQUEST_PROTOCOL_VERSION = "x-buck-protocol-version";

  private final ProjectFilesystem filesystem;
  private final DoctorConfig doctorConfig;
  private final BuckEventBus buckEventBus;
  private final Clock clock;

  public DefaultDefectReporter(
      ProjectFilesystem filesystem,
      DoctorConfig doctorConfig,
      BuckEventBus buckEventBus,
      Clock clock) {
    this.filesystem = filesystem;
    this.doctorConfig = doctorConfig;
    this.buckEventBus = buckEventBus;
    this.clock = clock;
  }

  private void addFilesToArchive(CustomZipOutputStream out, ImmutableSet<Path> paths)
      throws IOException {
    for (Path logFile : paths) {
      Path destPath = logFile;
      if (destPath.isAbsolute()) {
        // If it's an absolute path, make it relative instead
        destPath = destPath.subpath(0, logFile.getNameCount());
        Preconditions.checkArgument(!destPath.isAbsolute(), "Should be a relative path", destPath);
      }
      if (destPath.getFileName().toString().startsWith(".")) {
        // If the file is hidden(UNIX terms) save it as normal file.
        destPath =
            Optional.ofNullable(destPath.getParent())
                .orElse(Paths.get(""))
                .resolve(destPath.getFileName().toString().replaceAll("^\\.*", ""));
      }

      out.putNextEntry(new CustomZipEntry(destPath));

      try (InputStream input = filesystem.newFileInputStream(logFile)) {
        ByteStreams.copy(input, out);
      }
      out.closeEntry();
    }
  }

  private void addNamedFilesToArchive(
      CustomZipOutputStream out,
      ImmutableMap<String, VersionControlSupplier<InputStream>> fileStreams)
      throws IOException, VersionControlCommandFailedException, InterruptedException {
    for (Map.Entry<String, VersionControlSupplier<InputStream>> fs : fileStreams.entrySet()) {
      try (InputStream input = fs.getValue().get()) {
        out.writeEntry(fs.getKey(), input);
      }
      out.closeEntry();
    }
  }

  @Override
  public DefectSubmitResult submitReport(DefectReport defectReport) throws IOException {
    DefectSubmitResult.Builder defectSubmitResult = DefectSubmitResult.builder();
    defectSubmitResult.setRequestProtocol(doctorConfig.getProtocolVersion());
    Optional<SlbBuckConfig> frontendConfig = doctorConfig.getFrontendConfig();

    if (frontendConfig.isPresent()) {
      Optional<ClientSideSlb> slb =
          frontendConfig.get().tryCreatingClientSideSlb(clock, buckEventBus);
      if (slb.isPresent()) {
        try {
          return uploadReport(defectReport, defectSubmitResult, slb.get());
        } catch (IOException e) {
          LOG.debug(e, "Failed uploading report to server.");
          defectSubmitResult.setIsRequestSuccessful(false);
          defectSubmitResult.setReportSubmitErrorMessage(e.getMessage());
        }
      }
    }

    filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
    Path defectReportPath =
        filesystem.createTempFile(filesystem.getBuckPaths().getBuckOut(), "defect_report", ".zip");
    try (OutputStream outputStream = filesystem.newFileOutputStream(defectReportPath)) {
      writeReport(defectReport, outputStream);
    }

    return defectSubmitResult
        .setIsRequestSuccessful(Optional.empty())
        .setReportSubmitLocation(defectReportPath.toString())
        .build();
  }

  private void writeReport(DefectReport defectReport, OutputStream outputStream)
      throws IOException {
    try (BufferedOutputStream baseOut = new BufferedOutputStream(outputStream);
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(baseOut, APPEND_TO_ZIP)) {

      try {
        if (defectReport.getSourceControlInfo().isPresent()
            && defectReport.getSourceControlInfo().get().getDiff().isPresent()) {
          addNamedFilesToArchive(
              out,
              ImmutableMap.of(
                  DIFF_FILE_NAME, defectReport.getSourceControlInfo().get().getDiff().get()));
        }
      } catch (VersionControlCommandFailedException | InterruptedException e) {
        // log the exceptions thrown from VersionControlSupplier<InputStream> when generating diff
        LOG.warn(
            e,
            "Failed to gather diff from source control. Some diff information may be missing from the report");
      }
      addFilesToArchive(out, defectReport.getIncludedPaths());

      out.putNextEntry(new CustomZipEntry(REPORT_FILE_NAME));
      ObjectMappers.WRITER.writeValue(out, defectReport);
    }
  }

  private DefectSubmitResult uploadReport(
      DefectReport defectReport, DefectSubmitResult.Builder defectSubmitResult, ClientSideSlb slb)
      throws IOException {
    long timeout = doctorConfig.getReportTimeoutMs();
    OkHttpClient httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build();
    HttpService httpService =
        new RetryingHttpService(
            buckEventBus,
            new LoadBalancedService(slb, httpClient, buckEventBus),
            "buck_defect_reporter_http_retries",
            doctorConfig.getReportMaxUploadRetries());

    try {
      Request.Builder requestBuilder = new Request.Builder();
      requestBuilder.addHeader(
          REQUEST_PROTOCOL_VERSION, doctorConfig.getProtocolVersion().name().toLowerCase());
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

      HttpResponse response =
          httpService.makeRequest(doctorConfig.getReportUploadPath(), requestBuilder);
      String responseBody;
      try (InputStream inputStream = response.getBody()) {
        responseBody = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
      }

      if (response.statusCode() == HTTP_SUCCESS_CODE) {
        defectSubmitResult.setIsRequestSuccessful(true);
        if (doctorConfig.getProtocolVersion().equals(DoctorProtocolVersion.SIMPLE)) {
          return defectSubmitResult
              .setReportSubmitMessage(responseBody)
              .setReportSubmitLocation(responseBody)
              .build();
        } else {
          // Decode Json response.
          DoctorJsonResponse json =
              ObjectMappers.READER.readValue(
                  ObjectMappers.createParser(responseBody.getBytes(Charsets.UTF_8)),
                  DoctorJsonResponse.class);
          return defectSubmitResult
              .setIsRequestSuccessful(json.getRequestSuccessful())
              .setReportSubmitErrorMessage(json.getErrorMessage())
              .setReportSubmitMessage(json.getMessage())
              .setReportSubmitLocation(json.getRageUrl())
              .build();
        }
      } else {
        throw new IOException(
            String.format(
                "Connection to %s returned code %d and message: %s",
                response.requestUrl(), response.statusCode(), responseBody));
      }
    } catch (IOException e) {
      throw new IOException(String.format("Failed uploading report because [%s].", e.getMessage()));
    } finally {
      httpService.close();
    }
  }
}
