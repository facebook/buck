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

package com.facebook.buck.java.tracing;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.infer.annotation.Assertions;
import com.google.common.collect.ImmutableMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Helper class for creating and posting {@link JavacPhaseEvent}s.
 */
public class JavacPhaseEventLogger {
  private static final ImmutableMap<String, String> EMPTY_MAP = ImmutableMap.of();

  private final BuildTarget buildTarget;
  private final BuckEventBus buckEventBus;

  private final Map<JavacPhaseEvent.Phase, JavacPhaseEvent.Started> currentPhaseEvents =
      new HashMap<JavacPhaseEvent.Phase, JavacPhaseEvent.Started>();

  public JavacPhaseEventLogger(BuildTarget buildTarget, BuckEventBus buckEventBus) {
    this.buildTarget = buildTarget;
    this.buckEventBus = buckEventBus;
  }

  public void beginParse(String filename) {
    postStartedEvent(JavacPhaseEvent.Phase.PARSE, getArgs(filename, null));
  }

  public void endParse() {
    postFinishedEvent(JavacPhaseEvent.Phase.PARSE, EMPTY_MAP);
  }

  public void beginEnter() {
    postStartedEvent(JavacPhaseEvent.Phase.ENTER, EMPTY_MAP);
  }

  public void endEnter(List<String> filenames) {
    ImmutableMap.Builder<String, String> argsBuilder = ImmutableMap.builder();
    for (int i = 0; i < filenames.size(); i++) {
      argsBuilder.put(Integer.toString(i + 1), filenames.get(i));
    }
    postFinishedEvent(JavacPhaseEvent.Phase.ENTER, argsBuilder.build());
  }

  public void beginAnnotationProcessing() {
    postStartedEvent(JavacPhaseEvent.Phase.ANNOTATION_PROCESSING, EMPTY_MAP);
  }

  public void endAnnotationProcessing() {
    postFinishedEvent(JavacPhaseEvent.Phase.ANNOTATION_PROCESSING, EMPTY_MAP);
  }

  public void beginAnnotationProcessingRound(int roundNumber) {
    postStartedEvent(
        JavacPhaseEvent.Phase.ANNOTATION_PROCESSING_ROUND,
        getRoundNumberArgs(roundNumber));
  }

  public void endAnnotationProcessingRound(boolean isLastRound) {
    postFinishedEvent(
        JavacPhaseEvent.Phase.ANNOTATION_PROCESSING_ROUND,
        getIsLastRoundArgs(isLastRound));
  }

  public void beginRunAnnotationProcessors() {
    postStartedEvent(JavacPhaseEvent.Phase.RUN_ANNOTATION_PROCESSORS, EMPTY_MAP);
  }

  public void endRunAnnotationProcessors() {
    postFinishedEvent(JavacPhaseEvent.Phase.RUN_ANNOTATION_PROCESSORS, EMPTY_MAP);
  }

  public void beginAnalyze(@Nullable String filename, @Nullable String typename) {
    postStartedEvent(JavacPhaseEvent.Phase.ANALYZE, getArgs(filename, typename));
  }

  public void endAnalyze() {
    postFinishedEvent(JavacPhaseEvent.Phase.ANALYZE, EMPTY_MAP);
  }

  public void beginGenerate(@Nullable String filename, @Nullable String typename) {
    postStartedEvent(JavacPhaseEvent.Phase.GENERATE, getArgs(filename, typename));
  }

  public void endGenerate() {
    postFinishedEvent(JavacPhaseEvent.Phase.GENERATE, EMPTY_MAP);
  }

  private void postStartedEvent(JavacPhaseEvent.Phase phase, ImmutableMap<String, String> args) {
    JavacPhaseEvent.Started startedEvent = JavacPhaseEvent.started(buildTarget, phase, args);

    Assertions.assertCondition(currentPhaseEvents.get(phase) == null);
    currentPhaseEvents.put(phase, startedEvent);

    buckEventBus.post(startedEvent);
  }

  private void postFinishedEvent(JavacPhaseEvent.Phase phase, ImmutableMap<String, String> args) {
    JavacPhaseEvent.Finished finishedEvent = JavacPhaseEvent.finished(
        Assertions.assertNotNull(currentPhaseEvents.get(phase)),
        args);

    currentPhaseEvents.remove(phase);

    buckEventBus.post(finishedEvent);
  }

  private ImmutableMap<String, String> getRoundNumberArgs(int roundNumber) {
    ImmutableMap.Builder<String, String> resultBuilder = ImmutableMap.builder();

    resultBuilder.put("round", Integer.toString(roundNumber));

    return resultBuilder.build();
  }

  private ImmutableMap<String, String> getIsLastRoundArgs(boolean isLastRound) {
    ImmutableMap.Builder<String, String> resultBuilder = ImmutableMap.builder();

    resultBuilder.put("last round", Boolean.toString(isLastRound));

    return resultBuilder.build();
  }

  private ImmutableMap<String, String> getArgs(@Nullable String file, @Nullable String type) {
    ImmutableMap.Builder<String, String> resultBuilder = ImmutableMap.builder();

    if (file != null) {
      resultBuilder.put("file", file);
    }

    if (type != null) {
      resultBuilder.put("type", type);
    }

    return resultBuilder.build();
  }
}
