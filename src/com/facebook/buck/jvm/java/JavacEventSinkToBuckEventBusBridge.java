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

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.event.BuckTracingEventBusBridge;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent.Operation;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEvent;
import com.facebook.buck.util.types.Pair;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public class JavacEventSinkToBuckEventBusBridge implements JavacEventSink {

  private final IsolatedEventBus eventBus;
  private final ActionId actionId;
  private final LoadingCache<String, BuckTracingEventBusBridge> buckTracingBridgeCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<String, BuckTracingEventBusBridge>() {
                @Override
                public BuckTracingEventBusBridge load(String target) {
                  return new BuckTracingEventBusBridge(eventBus, actionId, target);
                }
              });
  private final Map<Pair<String, JavacPhaseEvent.Phase>, JavacPhaseEvent.Started>
      currentJavacPhaseEvents = new ConcurrentHashMap<>();

  private final Map<String, EventKey> startedAnnotationProcessingEvents = new ConcurrentHashMap<>();
  private final Map<Long, SimplePerfEvent.Scope> perfEventScopes = new ConcurrentHashMap<>();

  public JavacEventSinkToBuckEventBusBridge(IsolatedEventBus eventBus, ActionId actionId) {
    this.eventBus = eventBus;
    this.actionId = actionId;
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
      String buildTargetName) {
    try {
      return buckTracingBridgeCache.get(buildTargetName);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void reportCompilerPluginStarted(
      String buildTargetName,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    getBuckTracingEventBusBridgeForBuildTarget(buildTargetName)
        .begin(pluginName, durationName, args);
  }

  @Override
  public void reportCompilerPluginFinished(
      String buildTargetName, ImmutableMap<String, String> args) {
    getBuckTracingEventBusBridgeForBuildTarget(buildTargetName).end(args);
  }

  @Override
  public void reportJavacPhaseStarted(
      String buildTargetName, String phaseAsString, ImmutableMap<String, String> args) {
    JavacPhaseEvent.Phase phase = JavacPhaseEvent.Phase.fromString(phaseAsString);
    JavacPhaseEvent.Started startedEvent = JavacPhaseEvent.started(buildTargetName, phase, args);

    Pair<String, JavacPhaseEvent.Phase> key = new Pair<>(buildTargetName, phase);
    Assertions.assertCondition(currentJavacPhaseEvents.get(key) == null);
    currentJavacPhaseEvents.put(key, startedEvent);

    eventBus.post(startedEvent, actionId);
  }

  @Override
  public void reportJavacPhaseFinished(
      String buildTargetName, String phaseAsString, ImmutableMap<String, String> args) {
    Pair<String, JavacPhaseEvent.Phase> key =
        new Pair<>(buildTargetName, JavacPhaseEvent.Phase.fromString(phaseAsString));
    JavacPhaseEvent.Finished finishedEvent =
        JavacPhaseEvent.finished(Assertions.assertNotNull(currentJavacPhaseEvents.get(key)), args);
    currentJavacPhaseEvents.remove(key);

    eventBus.post(finishedEvent, actionId);
  }

  @Override
  public void reportAnnotationProcessingEventStarted(
      String buildTargetName,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    AnnotationProcessingEvent.Started started =
        AnnotationProcessingEvent.started(
            buildTargetName,
            annotationProcessorName,
            AnnotationProcessingEvent.Operation.valueOf(operationAsString),
            round,
            isLastRound);
    startedAnnotationProcessingEvents.put(
        getKeyForAnnotationProcessingEvent(
            buildTargetName, annotationProcessorName, operationAsString, round, isLastRound),
        started.getEventKey());
    eventBus.post(started, actionId);
  }

  @Override
  public void reportAnnotationProcessingEventFinished(
      String buildTargetName,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    EventKey startedEventKey =
        startedAnnotationProcessingEvents.get(
            getKeyForAnnotationProcessingEvent(
                buildTargetName, annotationProcessorName, operationAsString, round, isLastRound));
    AnnotationProcessingEvent.Finished finished =
        new AnnotationProcessingEvent.Finished(
            Objects.requireNonNull(startedEventKey),
            buildTargetName,
            annotationProcessorName,
            Operation.valueOf(operationAsString),
            round,
            isLastRound);
    eventBus.post(finished, actionId);
  }

  private String getKeyForAnnotationProcessingEvent(
      String buildTargetName,
      String annotationProcessorName,
      String operationAsString,
      int round,
      boolean isLastRound) {
    return Joiner.on(":")
        .join(buildTargetName, annotationProcessorName, operationAsString, round, isLastRound);
  }

  @Override
  public void startSimplePerfEvent(String name, long uniqueKey) {
    SimplePerfEvent.Scope scope = SimplePerfEvent.scopeWithActionId(eventBus, actionId, name);
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

  @Override
  public void waitTillAllEventsProcessed() {
    eventBus.waitTillAllEventsProcessed();
  }
}
