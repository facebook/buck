/*
 * Copyright 2015-present Facebook, Inc.
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

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

import javax.annotation.Nullable;

public class HttpScribeLogger implements ScribeLogger {

  public static final String ENDPOINT = "https://interngraph.intern.facebook.com/scribe/log";
  public static final int MAX_REQUEST_SIZE_BYTES = 1024 * 1024;
  public static final int MAX_PARALLEL_REQUESTS = 5;

  private static final String APP_ID = "480696962007533";
  private static final String TOKEN = "AeNzhrutjcZ0ew5LBlo";

  private final HttpEndpoint httpEndpoint;
  private final int maxRequestSizeBytes;

  public HttpScribeLogger(
      HttpEndpoint httpEndpoint,
      int maxRequestSizeBytes) {
    this.httpEndpoint = httpEndpoint;
    this.maxRequestSizeBytes = maxRequestSizeBytes;
  }

  private <T> ListenableFuture<Void> toVoid(ListenableFuture<T> future) {
    return Futures.transform(
        future,
        new Function<T, Void>() {
          @Nullable
          @Override
          public Void apply(T input) {
            return null;
          }
        });
  }

  private ListenableFuture<Void> submit(String category, StringBuilder lines) {
    String header =
        String.format(
            "app=%s&token=%s&category=%s&loglines=",
            APP_ID,
            TOKEN,
            category);
    return toVoid(httpEndpoint.post(header + lines));
  }

  @Override
  public ListenableFuture<Void> log(String category, Iterable<String> lines) {
    List<ListenableFuture<Void>> responses = Lists.newArrayList();

    StringBuilder concatenatedLines = new StringBuilder();

    for (String line : lines) {
      StringBuilder toAdd = new StringBuilder();

      // If we've already added a line to the request, then add a line terminator before we
      // add this one.
      if (concatenatedLines.length() > 0) {
        toAdd.append('\n');
      }

      // Add the URL encoded line.
      try {
        toAdd.append(URLEncoder.encode(line, "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        return Futures.immediateFailedFuture(Throwables.propagate(e));
      }

      // If adding this line would push us over the request size limit, dispatch the current
      // request, and start a new one.
      if (concatenatedLines.length() + toAdd.length() >= maxRequestSizeBytes) {
        responses.add(submit(category, concatenatedLines));
        concatenatedLines = new StringBuilder();
      }

      concatenatedLines.append(toAdd);
    }

    // If there is anything remaining in the current request, dispatch it.
    if (concatenatedLines.length() > 0) {
      responses.add(submit(category, concatenatedLines));
    }

    // Flatten the list of void futures to single void future.
    return toVoid(Futures.allAsList(responses));
  }

}
