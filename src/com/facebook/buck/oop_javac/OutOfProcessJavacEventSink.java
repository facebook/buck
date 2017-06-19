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

package com.facebook.buck.oop_javac;

import com.facebook.buck.jvm.java.JavacEventSink;
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableMap;
import java.util.logging.Level;

public class OutOfProcessJavacEventSink implements JavacEventSink {
  @Override
  public void reportThrowable(Throwable throwable, String message, Object... args) {}

  @Override
  public void reportEvent(Level level, String message, Object... args) {}

  @Override
  public void reportCompilerPluginStarted(
      BuildTarget buildTarget,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {}

  @Override
  public void reportCompilerPluginFinished(
      BuildTarget buildTarget, ImmutableMap<String, String> args) {}

  @Override
  public void reportJavacPhaseStarted(
      BuildTarget buildTarget, String phase, ImmutableMap<String, String> args) {}

  @Override
  public void reportJavacPhaseFinished(
      BuildTarget buildTarget, String phase, ImmutableMap<String, String> args) {}

  @Override
  public void reportAnnotationProcessingEventStarted(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operation,
      int round,
      boolean isLastRound) {}

  @Override
  public void reportAnnotationProcessingEventFinished(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operation,
      int round,
      boolean isLastRound) {}

  @Override
  public void startSimplePerfEvent(String name, long uniqueKey) {}

  @Override
  public void stopSimplePerfEvent(long uniqueKey) {}
}
