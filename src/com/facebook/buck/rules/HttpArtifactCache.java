/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpArtifactCache implements ArtifactCache {
  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final int MAX_CONNECTION_FAILURE_REPORTS = 1;
  private static final String URL_TEMPLATE_FETCH = "http://%s:%d/artifact/key/%s";
  private static final String URL_TEMPLATE_STORE = "http://%s:%d/artifact/";
  private static final Logger logger = Logger.get(HttpArtifactCache.class);
  private static final String BOUNDARY = "buckcacheFormPartBoundaryCHk4TK4bRHXDX0cICpSAbBXWzkXbtt";

  private final AtomicInteger numConnectionExceptionReports;
  private final String hostname;
  private final int port;
  private final int timeoutSeconds;
  private final boolean doStore;
  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final FileHashCache fileHashCache;
  private final String urlStore;

  public HttpArtifactCache(
      String hostname,
      int port,
      int timeoutSeconds,
      boolean doStore,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache) {
    Preconditions.checkArgument(0 <= port && port < 65536);
    Preconditions.checkArgument(1 <= timeoutSeconds);
    this.hostname = hostname;
    this.port = port;
    this.timeoutSeconds = timeoutSeconds;
    this.doStore = doStore;
    this.projectFilesystem = projectFilesystem;
    this.buckEventBus = buckEventBus;
    this.fileHashCache = fileHashCache;
    this.numConnectionExceptionReports = new AtomicInteger(0);
    this.urlStore = String.format(URL_TEMPLATE_STORE, hostname, port);
  }

  protected HttpURLConnection getConnection(String url) throws MalformedURLException, IOException {
    return (HttpURLConnection) new URL(url).openConnection();
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File file) {
    String url = String.format(URL_TEMPLATE_FETCH, hostname, port, ruleKey.toString());
    HttpURLConnection connection;
    try {
      connection = getConnection(url);
      connection.setConnectTimeout(1000 * timeoutSeconds);
    } catch (MalformedURLException e) {
      logger.error(e, "fetch(%s): malformed URL: %s", ruleKey, url);
      return CacheResult.MISS;
    } catch (IOException e) {
      logger.warn(e, "fetch(%s): [init] IOException: %s", ruleKey, e.getMessage());
      return CacheResult.MISS;
    }

    int responseCode;
    try {
      responseCode = connection.getResponseCode();
    } catch (IOException e) {
      reportConnectionFailure(String.format("fetch(%s)", ruleKey), e);
      return CacheResult.MISS;
    }

    switch (responseCode) {
      case HttpURLConnection.HTTP_OK:
        try (InputStream input = connection.getInputStream()) {

          // Setup an object input stream to deserialize the hash code.
          try (ObjectInputStream objectStream = new ObjectInputStream(input)) {

            // First, extract the hash code from the beginning of the request data.
            HashCode expectedHashCode;
            try {
              expectedHashCode = (HashCode) objectStream.readObject();
            } catch (ClassNotFoundException | ClassCastException e) {
              logger.warn("fetch(%s): could not deserialize artifact checksum", ruleKey);
              return CacheResult.MISS;
            }

            // Setup a temporary file, which sits next to the destination, to write to and
            // make sure all parent dirs exist.
            Path path = file.toPath();
            projectFilesystem.createParentDirs(path);
            Path temp = projectFilesystem.createTempFile(
                path.getParent(),
                path.getFileName().toString(),
                ".tmp");

            // Write the remaining response data to the temp file.
            projectFilesystem.copyToPath(input, temp, StandardCopyOption.REPLACE_EXISTING);

            // Now form the checksum on the file we got and compare it to the checksum form the
            // the HTTP header.  If it's incorrect, log this and return a miss.
            HashCode actualHashCode = fileHashCache.get(temp);
            if (!expectedHashCode.equals(actualHashCode)) {
              logger.warn("fetch(%s): artifact had invalid checksum", ruleKey);
              projectFilesystem.deleteFileAtPath(temp);
              return CacheResult.MISS;
            }

            // Finally, move the temp file into it's final place.
            projectFilesystem.move(temp, path, StandardCopyOption.REPLACE_EXISTING);

          }
        } catch (IOException e) {
          logger.warn(e, "fetch(%s): [write] IOException: %s", ruleKey, e.getMessage());
          return CacheResult.MISS;
        }
        logger.info("fetch(%s): cache hit", ruleKey);
        return CacheResult.HTTP_HIT;
      case HttpURLConnection.HTTP_NOT_FOUND:
        logger.info("fetch(%s): cache miss", ruleKey);
        return CacheResult.MISS;
      default:
        logger.warn("fetch(%s): unexpected response: %d", ruleKey, responseCode);
        return CacheResult.MISS;
    }
  }

  @Override
  public void store(RuleKey ruleKey, File file) {
    if (!isStoreSupported()) {
      return;
    }
    String method = "POST";
    HttpURLConnection connection;
    try {
      connection = getConnection(urlStore);
      connection.setConnectTimeout(1000 * timeoutSeconds);
      connection.setRequestMethod(method);
      // Use "chunked" streaming mode so that we don't buffer the entire contents of the artifact
      // in memory.  Using "0" here as the value causes an internal default to be used (4096).
      connection.setChunkedStreamingMode(0);
      prepareFileUpload(connection, file, ruleKey.toString());
    } catch (NotSerializableException e) {
      logger.error(e, "store(%s): could not write hash code: %s", ruleKey);
      return;
    } catch (MalformedURLException e) {
      logger.error(e, "store(%s): malformed URL: %s", ruleKey, urlStore);
      return;
    } catch (ProtocolException e) {
      logger.error(e, "store(%s): invalid protocol: %s", ruleKey, method);
      return;
    } catch (ConnectException e) {
      reportConnectionFailure(String.format("store(%s)", ruleKey), e);
      return;
    } catch (IOException e) {
      logger.warn(e, "store(%s): IOException: %s", ruleKey, e.getMessage());
      return;
    }

    int responseCode;
    try {
      responseCode = connection.getResponseCode();
    } catch (IOException e) {
      reportConnectionFailure(String.format("store(%s)", ruleKey), e);
      return;
    }
    if (responseCode != HttpURLConnection.HTTP_ACCEPTED) {
      logger.warn("store(%s): unexpected response: %d", ruleKey, responseCode);
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  @Override
  public void close() {
    int failures = numConnectionExceptionReports.get();
    if (failures > 0) {
      logger.warn("Total connection failures: %s", failures);
    }
    return;
  }

  private void reportConnectionFailure(String context, Exception exception) {
    if (numConnectionExceptionReports.getAndIncrement() < MAX_CONNECTION_FAILURE_REPORTS) {
      buckEventBus.post(ConsoleEvent.warning(
              "%s: Connection failed: %s",
              context,
              exception.getMessage()));
    }
  }

  private void prepareFileUpload(HttpURLConnection connection, File file, String key)
      throws IOException {
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
    // The cache protocol requires we provide the number of artifacts being sent in the request
    connection.setRequestProperty("Buck-Artifact-Count", "1");
    try (OutputStream os = new BufferedOutputStream(connection.getOutputStream());
         InputStream is = projectFilesystem.newFileInputStream(file.toPath());) {
      os.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
      os.write("Content-Disposition: form-data; name=\"key0\"\r\n\r\n".getBytes(
            StandardCharsets.UTF_8));
      os.write(key.getBytes(StandardCharsets.UTF_8));
      os.write(("\r\n--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
      os.write("Content-Disposition: form-data; name=\"data0\"\r\n"
          .getBytes(StandardCharsets.UTF_8));
      os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));

      // Setup an object output stream to serialize the hash code.
      try (ObjectOutputStream objectStream = new ObjectOutputStream(os)) {

        // Grab the hash code of the file contents and serialize it to the beginning of the
        // request data.
        objectStream.writeObject(fileHashCache.get(file.toPath()));
        objectStream.flush();

        ByteStreams.copy(is, os);
        os.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
      }
    }
  }
}
