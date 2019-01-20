/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ImmutableMap;
import java.util.logging.Level;

/**
 * This is an abstraction on top of event bus. For various places we need to have a unified code
 * that will work for both in process and out of process javac. We can't transfer event bus to
 * another process, but we can abstract it and then re-fill the main process' event bus with events.
 */
public interface JavacEventSink {
  void reportThrowable(Throwable throwable, String message, Object... args);

  void reportEvent(Level level, String message, Object... args);

  void reportCompilerPluginStarted(
      BuildTarget buildTarget,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args);

  void reportCompilerPluginFinished(BuildTarget buildTarget, ImmutableMap<String, String> args);

  void reportJavacPhaseStarted(
      BuildTarget buildTarget, String phase, ImmutableMap<String, String> args);

  void reportJavacPhaseFinished(
      BuildTarget buildTarget, String phase, ImmutableMap<String, String> args);

  void reportAnnotationProcessingEventStarted(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operation,
      int round,
      boolean isLastRound);

  void reportAnnotationProcessingEventFinished(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operation,
      int round,
      boolean isLastRound);

  /**
   * There could be several perf events with the same name. Since event sink can't pass started
   * events into finished events, we use some shared key between started and finished. This allows
   * us to reconstruct started-finished pairs later.
   *
   * @param name Name of event. This should match in both Start and Stop method calls.
   * @param uniqueKey Unique key. This should match in both Start and Stop method calls.
   */
  void startSimplePerfEvent(String name, long uniqueKey);

  /** @param uniqueKey Unique key. This should match in both Start and Stop method calls. */
  void stopSimplePerfEvent(long uniqueKey);
}
