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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.AuthAwareDownloader;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.DownloadEvent.Started;
import com.google.common.io.BaseEncoding;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Download a file over HTTP. */
public class HttpDownloader implements Downloader, AuthAwareDownloader {
  private static final int PROGRESS_REPORT_EVERY_N_BYTES = 1000;

  private static final Logger LOG = Logger.get(HttpDownloader.class);

  private final Optional<Proxy> proxy;

  public HttpDownloader() {
    this(Optional.empty());
  }

  public HttpDownloader(Optional<Proxy> proxy) {
    this.proxy = proxy;
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    return fetch(eventBus, uri, Optional.empty(), output);
  }

  @Override
  public boolean fetch(
      BuckEventBus eventBus, URI uri, Optional<PasswordAuthentication> authentication, Path output)
      throws IOException {
    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
      return false;
    }

    Started started = DownloadEvent.started(uri);
    eventBus.post(started);

    try {
      HttpURLConnection connection = createConnection(uri);

      if (authentication.isPresent()) {
        if ("https".equals(uri.getScheme()) && connection instanceof HttpsURLConnection) {
          PasswordAuthentication p = authentication.get();
          String authStr = p.getUserName() + ":" + new String(p.getPassword());
          String authEncoded =
              BaseEncoding.base64().encode(authStr.getBytes(StandardCharsets.UTF_8));
          connection.addRequestProperty("Authorization", "Basic " + authEncoded);
        } else {
          LOG.info("Refusing to send basic authentication over plain http.");
          return false;
        }
      }

      if (HttpURLConnection.HTTP_OK != connection.getResponseCode()) {
        LOG.info("Unable to download %s: %s", uri, connection.getResponseMessage());
        return false;
      }
      long contentLength = connection.getContentLengthLong();
      try (InputStream is = new BufferedInputStream(connection.getInputStream());
          OutputStream os = new BufferedOutputStream(Files.newOutputStream(output))) {
        long read = 0;

        while (true) {
          int r = is.read();
          read++;
          if (r == -1) {
            break;
          }
          if (read % PROGRESS_REPORT_EVERY_N_BYTES == 0) {
            eventBus.post(new DownloadProgressEvent(uri, contentLength, read));
          }
          os.write(r);
        }
      }

      return true;
    } finally {
      eventBus.post(DownloadEvent.finished(started));
    }
  }

  protected HttpURLConnection createConnection(URI uri) throws IOException {
    HttpURLConnection connection;
    if (proxy.isPresent()) {
      connection = (HttpURLConnection) uri.toURL().openConnection(proxy.get());
    } else {
      connection = (HttpURLConnection) uri.toURL().openConnection();
    }
    connection.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(20));
    connection.setInstanceFollowRedirects(true);

    return connection;
  }
}
