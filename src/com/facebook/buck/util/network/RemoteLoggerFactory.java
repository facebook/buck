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

package com.facebook.buck.util.network;

import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.net.MalformedURLException;
import java.net.URI;

public abstract class RemoteLoggerFactory {

  public static final int MAX_PARALLEL_REQUESTS = 5;

  /**
   * @param uri URI to create the logger for.
   * @return The {@link RemoteLogger} instance matching the given scheme.
   */
  public static RemoteLogger create(URI uri, ObjectMapper objectMapper) {

    try {
      return new HttpPutLogger(
          new BlockingHttpEndpoint(
              uri.toString(),
              MAX_PARALLEL_REQUESTS,
              BlockingHttpEndpoint.DEFAULT_COMMON_TIMEOUT_MS),
          objectMapper);
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e, "Don't know how to upload logs to %s", uri);
    }
  }

  /**
   * Uploads log entries to the given scribe category.
   */
  public static class ScribeBatchingLogger extends BatchingLogger {

    private final ScribeLogger scribeLogger;
    private final String category;

    public ScribeBatchingLogger(URI uri, ScribeLogger scribeLogger) {
      this(uri.getSchemeSpecificPart(), scribeLogger);
    }

    public ScribeBatchingLogger(String category, ScribeLogger scribeLogger) {
      this.category = category;
      this.scribeLogger = scribeLogger;
    }

    public static boolean isValidScheme(URI uri) {
      return uri.getScheme().equals("scribe");
    }

    @Override
    protected ListenableFuture<Void> logMultiple(ImmutableCollection<BatchEntry> data) {
      ImmutableList<String> lines = FluentIterable.from(data)
          .transform(
              new Function<BatchEntry, String>() {
                @Override
                public String apply(BatchEntry input) {
                  return input.getLine();
                }
              }).toList();
      return scribeLogger.log(category, lines);
    }
  }
}
