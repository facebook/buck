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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.StringWriter;

import javax.annotation.Nullable;

/**
 * Uploads log entries in batches as newline-separated JSON strings in a HTTP PUT request.
 */
public class HttpPutLogger extends BatchingLogger {

  private final HttpEndpoint endpoint;
  private final ObjectMapper objectMapper;

  public HttpPutLogger(HttpEndpoint endpoint, ObjectMapper objectMapper) {
    this.endpoint = endpoint;
    this.objectMapper = objectMapper;
  }

  @Override
  protected ListenableFuture<Void> logMultiple(ImmutableCollection<BatchEntry> data) {
    StringWriter stringWriter = new StringWriter();
    try {
      JsonGenerator jsonGenerator = objectMapper.getJsonFactory().createJsonGenerator(stringWriter);
      jsonGenerator.writeStartArray();
      for (BatchEntry entry : data) {
        jsonGenerator.writeRawValue(entry.getLine());
      }
      jsonGenerator.writeEndArray();
      jsonGenerator.close();
      return Futures.transform(
          endpoint.post(stringWriter.toString()),
          new Function<HttpResponse, Void>() {
            @Nullable
            @Override
            public Void apply(HttpResponse input) {
              return null;
            }
          });
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }
  }
}
