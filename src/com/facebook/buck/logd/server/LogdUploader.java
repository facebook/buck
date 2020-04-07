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

package com.facebook.buck.logd.server;

import com.facebook.buck.logd.proto.LogType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** This class is responsible for uploading logs to storage. */
public class LogdUploader {
  private static final Logger LOG = LogManager.getLogger(LogdUploader.class);

  private static final String BASE_URL = System.getProperty("logd.controller.url", "");

  static {
    LOG.info("logd.controller.url: {}", BASE_URL);
  }

  private static final String UUID_FIELD_NAME = "uuid";
  private static final String LOG_TYPE_FIELD_NAME = "log_type";
  private static final String DATA_FIELD_NAME = "data";
  private static final String OFFSET_FIELD_NAME = "offset";

  private static final String MESSAGE_FIELD_NAME = "message";
  private static final String ERROR_FIELD_NAME = "error";

  private static final String SUCCESS_MESSAGE = "\"SUCCESS\"";
  private static final String EMPTY_MESSAGE = "\"\"";

  private static final String OBJECT_ALREADY_EXIST_ERROR_MESSAGE =
      "\"[409] Predicate Check Failed: NoExistingObject\"";
  private static final int NUM_RETRIES = 3;
  private static final OkHttpClient client = new OkHttpClient();

  /**
   * Sends a request to create log directory in storage
   *
   * @param buildId buck command's uuid
   */
  public static void sendCreateLogDirRequestToController(String buildId) {
    HttpUrl url =
        HttpUrl.get(BASE_URL).newBuilder().addQueryParameter(UUID_FIELD_NAME, buildId).build();
    LOG.info("Create log dir request url: {}", url.toString());
    Request request =
        new Request.Builder()
            .url(url)
            .post(new MultipartBody.Builder().addFormDataPart(UUID_FIELD_NAME, buildId).build())
            .build();

    for (int i = 0; i < NUM_RETRIES; i++) {
      try (Response response = client.newCall(request).execute()) {
        ObjectMapper objectMapper = new ObjectMapper();

        if (response.body() != null) {
          JsonNode root = objectMapper.readTree(response.body().byteStream());
          String errorMessage = root.get(ERROR_FIELD_NAME).toString();
          if (!errorMessage.equals(EMPTY_MESSAGE)) {
            if (errorMessage.equals(OBJECT_ALREADY_EXIST_ERROR_MESSAGE)) {
              LOG.info("Requested log directory already exists...");
              return;
            } else {
              LOG.info(
                  "Attempt #{}: Failed to create log dir in storage with error: {}",
                  i + 1,
                  errorMessage);
            }
          } else {
            LOG.info("Created log dir in storage: {}", root.get(MESSAGE_FIELD_NAME).toString());
            return;
          }
        }
      } catch (IOException e) {
        LOG.debug("Failed to send request: {}", url, e);
      }
    }
  }

  /**
   * Sends a request to create a log file in an existing log directory
   *
   * @param buildId buck command's uuid
   * @param logType log file's type
   */
  public static Optional<Integer> sendCreateLogFileRequestToController(
      String buildId, LogType logType) {
    HttpUrl url =
        HttpUrl.get(BASE_URL).newBuilder().addQueryParameter(UUID_FIELD_NAME, buildId).build();

    String logMessage =
        String.format(
            "Logging for %s...%s", logType.getValueDescriptor().getName(), System.lineSeparator());
    Request request =
        new Request.Builder()
            .url(url)
            .post(
                new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(LOG_TYPE_FIELD_NAME, logType.getValueDescriptor().getName())
                    .addFormDataPart(DATA_FIELD_NAME, logMessage)
                    .build())
            .build();

    for (int i = 0; i < NUM_RETRIES; i++) {
      try (Response response = client.newCall(request).execute()) {
        ObjectMapper objectMapper = new ObjectMapper();

        if (response.body() != null) {
          JsonNode root = objectMapper.readTree(response.body().byteStream());
          String errorMessage = root.get(ERROR_FIELD_NAME).toString();
          if (!errorMessage.equals(EMPTY_MESSAGE)) {
            if (errorMessage.equals(OBJECT_ALREADY_EXIST_ERROR_MESSAGE)) {
              LOG.info("Log file already exists...");
              return Optional.empty();
            } else {
              LOG.info(
                  "Attempt #{}: Failed to create log file in storage with error: {}",
                  i + 1,
                  errorMessage);
            }
          } else {
            LOG.info("Created log file in storage: {}", root.get(MESSAGE_FIELD_NAME).toString());

            return Optional.of(logMessage.getBytes(StandardCharsets.UTF_8).length);
          }
        }
      } catch (IOException e) {
        LOG.debug("Could not send request to endpoint", e);
      }
    }

    return Optional.empty();
  }

  /**
   * Appends logs to an existing file in storage.
   *
   * @param buildId buck command's uuid
   * @param logType log type
   * @param currentOffset size of log file in storage
   * @param logMessage log message to be appended
   * @return an Optional of the new file offset if append operation is successful or empty otherwise
   */
  public static Optional<Integer> uploadLogToStorage(
      String buildId, LogType logType, int currentOffset, String logMessage) {
    HttpUrl url =
        HttpUrl.get(BASE_URL).newBuilder().addQueryParameter(UUID_FIELD_NAME, buildId).build();

    Request request =
        new Request.Builder()
            .url(url)
            .post(
                new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(LOG_TYPE_FIELD_NAME, logType.getValueDescriptor().getName())
                    .addFormDataPart(DATA_FIELD_NAME, logMessage)
                    .addFormDataPart(OFFSET_FIELD_NAME, String.valueOf(currentOffset))
                    .build())
            .build();

    try (Response response = client.newCall(request).execute()) {
      ObjectMapper objectMapper = new ObjectMapper();

      if (response.body() != null) {
        JsonNode root = objectMapper.readTree(response.body().byteStream());
        String status = root.get(MESSAGE_FIELD_NAME).toString();
        if (status.equals(SUCCESS_MESSAGE)) {
          return Optional.of(currentOffset + logMessage.getBytes(StandardCharsets.UTF_8).length);
        } else {
          LOG.error(
              "Failed to append to {} at offset {}. Error: {}",
              logType.getValueDescriptor().getName(),
              currentOffset,
              root.toString());
        }
      }
    } catch (IOException e) {
      LOG.debug("Could not upload log to storage", e);
    }

    return Optional.empty();
  }
}
