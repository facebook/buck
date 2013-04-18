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

package com.facebook.buck.debug;

import com.google.common.eventbus.EventBus;

import java.io.PrintStream;

/**
 * Tracer for debugging slow points in code. Based on Google Closure's goog.debug.Tracer.
 */
public final class Tracer {

  private final long id;

  private Tracer(String comment, String type) {
    id = ThreadTrace.threadTracer.get().startTracer(comment, type);
  }

  public static Tracer startTracer(String comment) {
    return new Tracer(comment, null);
  }

  public static Tracer startTracer(String comment, String type) {
    return new Tracer(comment, type);
  }

  public static void addComment(String comment) {
    ThreadTrace.threadTracer.get().addComment(comment, null);
  }

  public static void addComment(String comment, String type) {
    ThreadTrace.threadTracer.get().addComment(comment, type);
  }

  public static void initCurrentTrace(long defaultThreshold, EventBus eventBus) {
    ThreadTrace.initCurrentTrace(defaultThreshold, eventBus);
  }

  public static void clearCurrentTrace() {
    ThreadTrace.clearCurrentTrace();
  }

  public static void clearAndPrintCurrentTrace(PrintStream stream) {
    ThreadTrace.clearAndPrintCurrentTrace(stream);
  }

  public static String getFormattedThreadTrace() {
    return ThreadTrace.threadTracer.get().getFormattedTrace();
  }

  public long stop() {
    return ThreadTrace.threadTracer.get().stopTracer(id, null);
  }

  public void stop(long silenceThresholdParam) {
    ThreadTrace.threadTracer.get().stopTracer(id, silenceThresholdParam);
  }
}
