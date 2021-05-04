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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.AnnotationProcessorPerfStats;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Keeps track of Annotation Processors perf events and generates a perf stats data object. */
public class AnnotationProcessorsLogger {

  private static final Logger LOG = Logger.get(AnnotationProcessorsLogger.class);

  private final Map<EventKey, AnnotationProcessingEvent.Started> apStartEvents = new HashMap<>();
  private final Map<String, List<AnnotationProcessorPerfStats.Builder>> ruleProcessorsStats =
      new HashMap<>();

  /**
   * Record the perf event internally. If this is the last event for a specific processor - a perf
   * stats data object is generated and returned.
   *
   * @return AnnotationProcessorPerfStats if all the events for the processor have arrived.
   */
  public Optional<AnnotationProcessorPerfStats> handleEvent(AnnotationProcessingEvent event) {

    String rule = event.getBuildTargetFullyQualifiedName();
    String processorName = event.getCategory();
    AnnotationProcessingEvent.Operation operation = event.getOperation();

    if (event instanceof AnnotationProcessingEvent.Started) {
      apStartEvents.put(event.getEventKey(), (AnnotationProcessingEvent.Started) event);
    } else if (event instanceof AnnotationProcessingEvent.Finished) {
      AnnotationProcessingEvent.Finished finishedEvent = (AnnotationProcessingEvent.Finished) event;
      AnnotationProcessingEvent.Started startedEvent = apStartEvents.remove(event.getEventKey());

      if (startedEvent == null) {
        LOG.warn(
            "Got a finished event without a start event. rule[%s] processorName[%s] event[%s]",
            rule, processorName, event);
        return Optional.empty();
      }

      long duration =
          TimeUnit.NANOSECONDS.toMillis(finishedEvent.getNanoTime() - startedEvent.getNanoTime());

      AnnotationProcessorPerfStats.Builder procPerfStatsBuilder =
          getPerfStatsBuilder(rule, processorName);
      switch (operation) {
        case INIT:
        case GET_SUPPORTED_SOURCE_VERSION:
        case GET_SUPPORTED_OPTIONS:
        case GET_SUPPORTED_ANNOTATION_TYPES:
          // The "Init time" is a combination of these operations,
          // like the Kapt plugin is doing for Kotlin builds.
          procPerfStatsBuilder.addInitTime(duration);
          break;
        case PROCESS:
          procPerfStatsBuilder.addRoundTime(duration);
          break;
        default:
        case GET_COMPLETIONS:
          break;
      }

      // If we got all the events for this processor, we can generate and return the data object.
      if (event.isLastRound()) {
        cleanStatsMapping(rule, procPerfStatsBuilder);
        return Optional.of(procPerfStatsBuilder.build());
      }
    }

    return Optional.empty();
  }

  private AnnotationProcessorPerfStats.Builder getPerfStatsBuilder(
      String rule, String processorName) {
    List<AnnotationProcessorPerfStats.Builder> processorsStats =
        ruleProcessorsStats.computeIfAbsent(rule, ignored -> new ArrayList<>());

    Optional<AnnotationProcessorPerfStats.Builder> builder =
        processorsStats.stream()
            .filter(it -> it.getProcessorName().equals(processorName))
            .findFirst();

    return builder.orElseGet(
        () -> {
          AnnotationProcessorPerfStats.Builder procPerfDataBuilder =
              new AnnotationProcessorPerfStats.Builder(processorName);
          processorsStats.add(procPerfDataBuilder);
          return procPerfDataBuilder;
        });
  }

  private void cleanStatsMapping(String rule, AnnotationProcessorPerfStats.Builder statsBuilder) {
    List<AnnotationProcessorPerfStats.Builder> processorsStats = ruleProcessorsStats.get(rule);
    if (processorsStats != null) {
      processorsStats.remove(statsBuilder);
    }
  }
}
