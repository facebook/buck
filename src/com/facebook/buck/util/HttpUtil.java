/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.util;

import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpUtil {

  /** Utility class: do not instantiate. */
  private HttpUtil() {}

  private static final int DEFAULT_PAYLOAD_TIMEOUT_MS = 5000;

  public static void sendPost(String payload, String endpoint) throws IOException {
    sendPost(payload, endpoint, DEFAULT_PAYLOAD_TIMEOUT_MS);
  }

  public static void sendPost(String payload, String endpoint, int timeout)
      throws IOException {
    HttpURLConnection connection = (HttpURLConnection)new URL(endpoint).openConnection();
    connection.setUseCaches(false);
    connection.setDoInput(true);
    connection.setDoOutput(true);
    connection.setConnectTimeout(timeout);
    connection.setReadTimeout(timeout);

    try (OutputStreamWriter output = new OutputStreamWriter(connection.getOutputStream())) {
      output.write(payload);
    }
    try (InputStream is = connection.getInputStream()) {
      // Read the full response.  If this fails, we want to report an error.
      ByteStreams.copy(is, ByteStreams.nullOutputStream());
    }
    int responseCode = connection.getResponseCode();
    if (responseCode != HttpURLConnection.HTTP_OK) {
      throw new IOException("Got non-ok response code from endpoint:" + responseCode);
    }
  }
}
