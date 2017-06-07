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

package com.facebook.buck.event.api;

import java.util.Collections;
import java.util.Map;

/**
 * Allows annotation processors and other compiler plug-ins to output tracing information to Buck's
 * trace files (when being run in-process in Buck). These methods do nothing if called within
 * another build system.
 */
public final class BuckTracing {
  /**
   * Buck can technically be invoked recursively within the same process, so we can't assume a
   * single process-wide implementation of {@link BuckTracingInterface}.
   */
  private static final InheritableThreadLocal<BuckTracingInterface> curThreadTracingInterface =
      new InheritableThreadLocal<>();

  private final String pluginName;

  private final TraceSection traceSection = new TraceSection();

  /**
   * Gets an instance of {@link BuckTracing} for tracing in the given plugin. All {@link
   * BuckTracing} instances are backed by the same trace buffer, so {@link #begin(String, Map)} and
   * {@link #end(Map)}} calls on a given thread must nest across all instances.
   */
  public static BuckTracing getInstance(String pluginName) {
    return new BuckTracing(pluginName);
  }

  private BuckTracing(String pluginName) {
    this.pluginName = pluginName;
  }

  public TraceSection traceSection(final String eventName) {
    begin(eventName);

    return traceSection;
  }

  /**
   * Records the beginning of a traced section, and returns an object that can be used within a
   * try-with-resources block to automatically end the section. The section will appear in the trace
   * labeled with eventName.
   */
  public TraceSection traceSection(final String eventName, final Map<String, String> args) {
    begin(eventName, args);

    return traceSection;
  }

  /**
   * Records the beginning of a traced section. The section will appear in the trace labeled with
   * eventName.
   *
   * <p>For best results, this call should be immediately before a try block, and a corresponding
   * call to {@link #end(Map)} should be in the finally block. Consider using {@link
   * #traceSection(String)} in a try-with-resources block instead.
   */
  public void begin(final String eventName) {
    begin(eventName, Collections.emptyMap());
  }

  /**
   * Records the beginning of a traced section. The section will appear in the trace labeled with
   * eventName, and the supplied arguments will be visible when the section is selected.
   *
   * <p>For best results, this call should be immediately before a try block, and a corresponding
   * call to {@link #end(Map)} should be in the finally block. Consider using {@link
   * #traceSection(String, Map)} in a try-with-resources block instead.
   */
  public void begin(final String eventName, final Map<String, String> args) {
    final BuckTracingInterface tracingInterface = curThreadTracingInterface.get();
    if (tracingInterface == null) {
      return;
    }

    tracingInterface.begin(pluginName, eventName, args);
  }

  /**
   * Records the end of the traced section started by the most recent call to {@link #begin(String,
   * Map)}, on <em>any</em> {@link BuckTracing} object, on the current thread.
   *
   * <p>For best results, this call should be in a finally block, with the corresponding {@link
   * #begin(String, Map)} call immediately before the try.
   */
  public void end() {
    end(Collections.emptyMap());
  }

  /**
   * Records the end of the traced section started by the most recent call to {@link #begin(String,
   * Map)}, on <em>any</em> {@link BuckTracing} object, on the current thread. Arguments supplied
   * here will be added to those supplied to {@link #begin(String, Map)}; conflicting entries will
   * be overwritten.
   *
   * <p>For best results, this call should be in a finally block, with the corresponding {@link
   * #begin(String, Map)} call immediately before the try.
   */
  public void end(final Map<String, String> args) {
    final BuckTracingInterface tracingInterface = curThreadTracingInterface.get();
    if (tracingInterface == null) {
      return;
    }

    tracingInterface.end(args);
  }

  /**
   * Used by Buck to connect this class to its tracing mechanism. There is no need to call this
   * method manually.
   */
  public static void setCurrentThreadTracingInterfaceFromJsr199Javac(
      final BuckTracingInterface buckTracingInterface) {
    curThreadTracingInterface.set(buckTracingInterface);
  }

  /**
   * Used by Buck to disconnect this class from its tracing mechanism. There is no need to call this
   * method manually.
   */
  public static void clearCurrentThreadTracingInterfaceFromJsr199Javac() {
    curThreadTracingInterface.set(null);
  }

  /**
   * An {@link AutoCloseable} that can be used in a try-with-resources block to ensure a trace
   * section is ended properly when exited.
   */
  public class TraceSection implements AutoCloseable {
    @Override
    public void close() {
      // We're just going to use the same instance of this thing over and over again, so don't
      // bother doing any kind of duplicate-close checking.
      end();
    }
  }
}
