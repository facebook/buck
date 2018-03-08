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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckTracingEventBusBridge;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.types.Pair;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class JavacEventSinkToBuckEventBusBridge implements JavacEventSink {
  private final BuckEventBus eventBus;
  private final LoadingCache<BuildTarget, BuckTracingEventBusBridge> buckTracingBridgeCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<BuildTarget, BuckTracingEventBusBridge>() {
                @Override
                public BuckTracingEventBusBridge load(@Nonnull BuildTarget target) {
                  return new BuckTracingEventBusBridge(eventBus, target);
                }
              });
  private final Map<Pair<BuildTarget, JavacPhaseEvent.Phase>, JavacPhaseEvent.Started>
      currentJavacPhaseEvents = new ConcurrentHashMap<>();

  private final Map<String, EventKey> startedAnnotationProcessingEvents = new ConcurrentHashMap<>();
  private final Map<Long, SimplePerfEvent.Scope> perfEventScopes = new ConcurrentHashMap<>();

  public JavacEventSinkToBuckEventBusBridge(BuckEventBus eventBus) {
    this.eventBus = eventBus;
  }

  @Override
  public void reportThrowable(Throwable throwable, String message, Object... args) {
    eventBus.post(ThrowableConsoleEvent.create(throwable, message, args));
  }

  @Override
  public void reportEvent(Level level, String message, Object... args) {
    eventBus.post(ConsoleEvent.create(level, message, args));
  }

  private BuckTracingEventBusBridge getBuckTracingEventBusBridgeForBuildTarget(
      BuildTarget buildTarget) {
    try {
      return buckTracingBridgeCache.get(buildTarget);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void reportCompilerPluginStarted(
      BuildTarget buildTarget,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    getBuckTracingEventBusBridgeForBuildTarget(buildTarget).begin(pluginName, durationName, args);
  }

  @Override
  public void reportCompilerPluginFinished(
      BuildTarget buildTarget, ImmutableMap<String, String> args) {
    getBuckTracingEventBusBridgeForBuildTarget(buildTarget).end(args);
  }

  @Override
  public void reportJavacPhaseStarted(
      BuildTarget buildTarget, String phaseAsString, ImmutableMap<String, String> args) {
    JavacPhaseEvent.Phase phase = JavacPhaseEvent.Phase.fromString(phaseAsString);
    JavacPhaseEvent.Started startedEvent = JavacPhaseEvent.started(buildTarget, phase, args);

    Pair<BuildTarget, JavacPhaseEvent.Phase> key = new Pair<>(buildTarget, phase);
    Assertions.assertCondition(currentJavacPhaseEvents.get(key) == null);
    currentJavacPhaseEvents.put(key, startedEvent);

    eventBus.post(startedEvent);
  }

  @Override
  public void reportJavacPhaseFinished(
      BuildTarget buildTarget, String phaseAsString, ImmutableMap<String, String> args) {
    Pair<BuildTarget, JavacPhaseEvent.Phase> key =
        new Pair<>(buildTarget, JavacPhaseEvent.Phase.fromString(phaseAsString));
    JavacPhaseEvent.Finished finishedEvent =
        JavacPhaseEvent.finished(Assertions.assertNotNull(currentJavacPhaseEvents.get(key)), args);
    currentJavacPhaseEvents.remove(key);

    eventBus.post(finishedEvent);
  }

  @Override
  public void reportAnnotationProcessingEventStarted(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    AnnotationProcessingEvent.Started started =
        AnnotationProcessingEvent.started(
            buildTarget,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.valueOf(operationAsString),
            round,
            isLastRound);
    startedAnnotationProcessingEvents.put(
        getKeyForAnnotationProcessingEvent(
            buildTarget, annotationProcessorName, operationAsString, round, isLastRound),
        started.getEventKey());
    eventBus.post(started);
  }

  @Override
  public void reportAnnotationProcessingEventFinished(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    EventKey startedEventKey =
        startedAnnotationProcessingEvents.get(
            getKeyForAnnotationProcessingEvent(
                buildTarget, annotationProcessorName, operationAsString, round, isLastRound));
    AnnotationProcessingEvent.Finished finished =
        new AnnotationProcessingEvent.Finished(
            Preconditions.checkNotNull(startedEventKey),
            buildTarget,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.valueOf(operationAsString),
            round,
            isLastRound);
    eventBus.post(finished);
  }

  private String getKeyForAnnotationProcessingEvent(
      BuildTarget buildTarget,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    return Joiner.on(":")
        .join(
            buildTarget.toString(), annotationProcessorName, operationAsString, round, isLastRound);
  }

  @Override
  public void startSimplePerfEvent(String name, long uniqueKey) {
    SimplePerfEvent.Scope scope = SimplePerfEvent.scope(eventBus, name);
    perfEventScopes.put(uniqueKey, scope);
  }

  @Override
  public void stopSimplePerfEvent(long uniqueKey) {
    SimplePerfEvent.Scope scope = perfEventScopes.remove(uniqueKey);
    if (scope != null) {
      scope.close();
    } else {
      throw new RuntimeException(
          String.format(
              "perfEventScopes is out of sync: missing a 'start' call with key '%d'", uniqueKey));
    }
  }
}
