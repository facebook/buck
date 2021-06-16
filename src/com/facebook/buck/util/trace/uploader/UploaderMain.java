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
import com.facebook.buck.util.trace.uploader.types.TraceKind;
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

/** Main class used for uploading provided trace file into provided url */
public final class UploaderMain {

  static {
    MacIpv6BugWorkaround.apply();
  }

  private static final int SUCCESS_EXIT_CODE = 0;
  private static final int ERROR_EXIT_CODE = 1;

  @Option(name = "--buildId", required = true)
  private String uuid;

  @Option(name = "--traceFilePath", required = true)
  private Path traceFilePath;

  @Option(name = "--traceKind", required = true)
  private TraceKind traceKind;

  @Option(name = "--baseUrl", required = true)
  private URI baseUrl;

  @Option(name = "--log", required = true)
  private File logFile;

  @Option(name = "--compressionType", required = true)
  private CompressionType compressionType;

  /** Main method */
  public static void main(String[] args) throws IOException {
    UploaderMain main = new UploaderMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      int exitCode = main.run();
      System.exit(exitCode);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(ERROR_EXIT_CODE);
    }
  }

  private int run() throws IOException {
    try (PrintWriter logWriter = new PrintWriter(logFile)) { // NOPMD
      return upload(logWriter);
    }
  }

  private int upload(PrintWriter logWriter) {
    Stopwatch timer = Stopwatch.createStarted();
    NamedTemporaryFile tempFile = null;
    try {
      logWriter.format("Build ID: %s%n", uuid);
      logWriter.format("Trace file: %s (%d) bytes%n", traceFilePath, Files.size(traceFilePath));

      HttpUrl url =
          Objects.requireNonNull(HttpUrl.get(baseUrl))
              .newBuilder()
              .addQueryParameter("uuid", uuid)
              .addQueryParameter("trace_file_kind", traceKind.toString())
              .build();

      Path fileToUpload;
      String mediaType;
      String traceName;
      if (compressionType == CompressionType.GZIP) {
        fileToUpload = gzip(traceFilePath);
        mediaType = "application/json+gzip";
        traceName = traceFilePath.getFileName().toString() + ".gz";

        logWriter.format("Compressed size: %d bytes%n", Files.size(fileToUpload));
        logWriter.println("Uploading compressed trace...");
      } else {
        fileToUpload = traceFilePath;
        mediaType = "application/data";
        traceName = traceFilePath.getFileName().toString();

        logWriter.println("Uploading trace...");
      }

      if (traceKind == TraceKind.BUILD_LOG) {
        // Copy the file to a temp file in case buck is still writing to it.
        // TODO launch uploader from buck *after* logs are flushed
        tempFile = new NamedTemporaryFile(uuid, ".log");
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
      PrintWriter logWriter, HttpUrl url, Path fileToUpload, String mediaType, String traceName)
      throws IOException {
    logWriter.format("Upload URL: %s%n", url);
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
        logWriter.println("Failed!");
        return ERROR_EXIT_CODE;
      }

      JsonNode root = objectMapper.readTree(body.byteStream());
      if (root.has("error")) {
        logWriter.format("Failed!%n%s%n", root);
        return ERROR_EXIT_CODE;
      } else if (root.has("uri")) {
        logWriter.format("Success!%nFind it at %s%n", root.get("uri").asText());
      } else {
        logWriter.println("Success!");
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
