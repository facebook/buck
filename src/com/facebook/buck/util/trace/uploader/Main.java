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

package com.facebook.buck.util.trace.uploader;

import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.network.MacIpv6BugWorkaround;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.facebook.buck.util.zip.BestCompressionGZIPOutputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public final class Main {

  private static final int SUCCESS_EXIT_CODE = 0;
  private static final int ERROR_EXIT_CODE = 1;

  @Option(name = "--buildId", required = true)
  private String uuid;

  @Option(name = "--traceFilePath", required = true)
  private Path traceFilePath;

  @Option(name = "--traceFileKind", required = true)
  private String traceFileKind;

  @Option(name = "--baseUrl", required = true)
  private URI baseUrl;

  @Option(name = "--log", required = true)
  private File logFile;

  @Option(name = "--compressionType", required = true)
  @Nullable
  private CompressionType compressionType;

  static {
    MacIpv6BugWorkaround.apply();
  }

  public static void main(String[] args) throws IOException {
    Main main = new Main();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      int exitCode = main.run();
      System.exit(exitCode);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private int run() throws IOException {
    try (PrintWriter log = new PrintWriter(logFile)) { // NOPMD
      return upload(log);
    }
  }

  private int upload(PrintWriter logWriter) {
    Stopwatch timer = Stopwatch.createStarted();
    NamedTemporaryFile tempFile = null;
    try {
      HttpUrl url =
          Objects.requireNonNull(HttpUrl.get(baseUrl))
              .newBuilder()
              .addQueryParameter("uuid", this.uuid)
              .addQueryParameter("trace_file_kind", this.traceFileKind)
              .build();
      Path fileToUpload = traceFilePath;
      String mediaType = "application/data";
      String traceName = traceFilePath.getFileName().toString();
      boolean compressionEnabled = false;
      if (compressionType != null) {
        switch (compressionType) {
          case GZIP:
            fileToUpload = gzip(traceFilePath);
            mediaType = "application/json+gzip";
            traceName = traceName + ".gz";
            compressionEnabled = true;
            break;
          case NONE:
            break;
        }
      }

      logWriter.format("Build ID: %s%n", uuid);
      logWriter.format("Trace file: %s (%d) bytes%n", traceFilePath, Files.size(traceFilePath));
      if (compressionEnabled) {
        logWriter.format("Compressed size: %d bytes%n", Files.size(fileToUpload));
      }
      logWriter.format("Upload URL: %s%n", url);
      if (compressionEnabled) {
        logWriter.format("Uploading compressed trace...%n");
      } else {
        logWriter.format("Uploading trace...%n");
      }

      if (traceFileKind.equals("build_log")) {
        // Copy the logWriter to a temp file in case buck is still writing to it.
        // TODO launch uploader from buck *after* logs are flushed
        tempFile = new NamedTemporaryFile(uuid, ".logWriter");
        Files.copy(fileToUpload, tempFile.get(), StandardCopyOption.REPLACE_EXISTING);
        fileToUpload = tempFile.get();
      }

      return upload(logWriter, url, fileToUpload, mediaType, traceName);

    } catch (Exception e) {
      logWriter.format("%nFailed to upload trace; %s%n", e.getMessage());
      e.printStackTrace(logWriter);
      return ERROR_EXIT_CODE;
    } finally {
      closeTempFile(logWriter, tempFile);
      logWriter.format("Elapsed time: %d millis", timer.elapsed(TimeUnit.MILLISECONDS));
    }
  }

  private int upload(
      PrintWriter log, HttpUrl url, Path fileToUpload, String mediaType, String traceName)
      throws IOException {
    Request request =
        new Request.Builder()
            .url(url)
            .post(
                new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "trace_file",
                        traceName,
                        RequestBody.create(MediaType.parse(mediaType), fileToUpload.toFile()))
                    .build())
            .build();

    OkHttpClient client = new OkHttpClient();
    try (Response response = client.newCall(request).execute()) {
      ObjectMapper objectMapper = new ObjectMapper();
      ResponseBody body = response.body();
      if (body == null) {
        log.println("Failed!");
        return ERROR_EXIT_CODE;
      }

      JsonNode root = objectMapper.readTree(body.byteStream());
      if (root.has("error")) {
        log.format("Failed!%n%s%n", root);
        return ERROR_EXIT_CODE;
      } else if (root.has("uri")) {
        log.format("Success!%nFind it at %s%n", root.get("uri").asText());
      } else {
        log.format("Success!%n");
      }
      return SUCCESS_EXIT_CODE;
    }
  }

  private void closeTempFile(PrintWriter logWriter, NamedTemporaryFile tempFile) {
    if (tempFile != null) {
      try {
        tempFile.close();
      } catch (IOException e) {
        logWriter.format("Failed to clean up temp file: %s%n", e.getMessage());
        e.printStackTrace(logWriter);
      }
    }
  }

  private Path gzip(Path uncompressed) throws IOException {
    Path compressed = Files.createTempFile("tmp", ".gz");
    try (BestCompressionGZIPOutputStream gzipStream =
        new BestCompressionGZIPOutputStream(
            new BufferedOutputStream(Files.newOutputStream(compressed)), true)) {
      Files.copy(uncompressed, gzipStream);
    }
    return compressed;
  }
}
