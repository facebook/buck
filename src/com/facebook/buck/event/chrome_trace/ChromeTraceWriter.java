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

package com.facebook.buck.event.chrome_trace;

import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Type-safe utility to write Chrome trace events to files.
 *
 * @see ChromeTraceEvent
 */
public class ChromeTraceWriter implements AutoCloseable {
  private final JsonGenerator jsonGenerator;

  /** Create a writer backed by specified output stream. */
  public ChromeTraceWriter(OutputStream traceStream) throws IOException {
    this(ObjectMappers.createGenerator(traceStream));
  }

  /** Create a writer backed by specified json generator. */
  public ChromeTraceWriter(JsonGenerator jsonGenerator) {
    this.jsonGenerator = jsonGenerator;
  }

  /** Write single event. */
  public void writeEvent(ChromeTraceEvent chromeTraceEvent) throws IOException {
    ObjectMappers.WRITER.writeValue(jsonGenerator, chromeTraceEvent);
  }

  /** Must be called prior to emitting first event to properly initialize stream. */
  public void writeStart() throws IOException {
    jsonGenerator.writeStartArray();
  }

  /** Must be called after all events to properly terminate event stream. */
  public void writeEnd() throws IOException {
    jsonGenerator.writeEndArray();
  }

  /** Close the underlying json generator. */
  @Override
  public void close() throws IOException {
    jsonGenerator.close();
  }
}
