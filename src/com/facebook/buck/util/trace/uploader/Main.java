/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.trace.uploader;

import com.facebook.buck.util.network.MacIpv6BugWorkaround;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.facebook.buck.util.zip.BestCompressionGZIPOutputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD this is just a log
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public final class Main {
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

  private PrintWriter log;

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
    log = new PrintWriter(logFile); // NOPMD this is just a log
    try {
      return upload();
    } finally {
      log.close();
    }
  }

  private int upload() {
    Stopwatch timer = Stopwatch.createStarted();
    try {
      OkHttpClient client = new OkHttpClient();
      HttpUrl url =
          HttpUrl.get(baseUrl)
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

      log.format("Build ID: %s\n", uuid);
      log.format("Trace file: %s (%d) bytes\n", traceFilePath, Files.size(traceFilePath));
      if (compressionEnabled) {
        log.format("Compressed size: %d bytes\n", Files.size(fileToUpload));
      }
      log.format("Upload URL: %s\n", url);
      if (compressionEnabled) {
        log.format("Uploading compressed trace...");
      } else {
        log.format("Uploading trace...");
      }

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

      try (Response response = client.newCall(request).execute()) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(response.body().byteStream());

        if (root.get("success").asBoolean()) {
          log.format("Success!\nFind it at %s\n", root.get("content").get("uri").asText());
          return 0;
        } else {
          log.format("Failed!\nMessage: %s\n", root.get("error").asText());
          return 1;
        }
      }
    } catch (Exception e) {
      log.format("\nFailed to upload trace; %s\n", e.getMessage());
      return 1;
    } finally {
      log.format("Elapsed time: %d millis", timer.elapsed(TimeUnit.MILLISECONDS));
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
