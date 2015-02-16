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

import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * HttpEndpoint implementation which only allows a certain number of concurrent requests to be in
 * flight at any given point in time.
 */
public class BlockingHttpEndpoint implements HttpEndpoint, Closeable {

  private static final Logger LOG = Logger.get(BlockingHttpEndpoint.class);

  public static final int DEFAULT_COMMON_TIMEOUT_MS = 5000;
  private URL url;
  private int timeoutMillis;
  private final ListeningExecutorService requestService;
  private static final ThreadFactory threadFactory =
      new ThreadFactoryBuilder().setNameFormat(BlockingHttpEndpoint.class.getSimpleName() + "-%d")
          .build();

  public BlockingHttpEndpoint(
      String url,
      int maxParallelRequests,
      int timeoutMillis) throws MalformedURLException {
    this.url = new URL(url);
    this.timeoutMillis = timeoutMillis;

    // Create an ExecutorService that blocks after N requests are in flight.  Taken from
    // http://www.springone2gx.com/blog/billy_newport/2011/05/there_s_more_to_configuring_threadpools_than_thread_pool_size
    LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(maxParallelRequests);
    ExecutorService executor = new ThreadPoolExecutor(maxParallelRequests,
        maxParallelRequests,
        2L,
        TimeUnit.MINUTES,
        workQueue,
        threadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy());
    requestService = MoreExecutors.listeningDecorator(executor);
  }

  @Override
  public ListenableFuture<HttpResponse> post(final String content) {
    return requestService.submit(
        new Callable<HttpResponse>() {
          @Override
          public HttpResponse call() {
            try {
              HttpURLConnection connection = buildConnection("POST");
              connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
              return send(connection, content);
            } catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }
        });
  }

  @VisibleForTesting
  HttpResponse send(final HttpURLConnection connection, final String content) throws IOException {
    try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
      out.writeBytes(content);
      out.flush();
      out.close();
      InputStream inputStream = connection.getInputStream();
      String response = CharStreams.toString(
          new InputStreamReader(inputStream, Charsets.UTF_8));
      return new HttpResponse(response);
    } finally {
      connection.disconnect();
    }
  }

  private HttpURLConnection buildConnection(String httpMethod) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) this.url.openConnection();
    connection.setUseCaches(false);
    connection.setDoOutput(true);
    connection.setConnectTimeout(timeoutMillis);
    connection.setReadTimeout(timeoutMillis);
    connection.setRequestMethod(httpMethod);
    return connection;
  }

  /**
   * Attempt to complete submitted requests on close so that as much information is recorded as
   * possible. This aids debugging when close is called during exception processing.
   */
  @Override
  public void close() {
    requestService.shutdown();
    try {
      if (!requestService.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
        LOG.warn(Joiner.on(System.lineSeparator()).join(
            "A BlockingHttpEndpoint failed to shut down within the standard timeout.",
            "Your build might have succeeded, but some requests made to ",
            this.url + " were probably lost.",
            "Here's some debugging information:",
            requestService.toString()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
