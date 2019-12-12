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

package com.facebook.buck.jvm.java;

import java.util.concurrent.atomic.AtomicLong;

public class JavacEventSinkScopedSimplePerfEvent implements AutoCloseable {

  private static final AtomicLong KEY_GENERATOR = new AtomicLong(0);

  private final JavacEventSink eventSink;
  private final long eventKey = KEY_GENERATOR.incrementAndGet();

  public JavacEventSinkScopedSimplePerfEvent(JavacEventSink eventSink, String name) {
    this.eventSink = eventSink;
    this.eventSink.startSimplePerfEvent(name, eventKey);
  }

  @Override
  public void close() {
    eventSink.stopSimplePerfEvent(eventKey);
  }
}
