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

import static java.lang.System.currentTimeMillis;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.AnnotationProcessorPerfStats;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent.Operation;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

/** Tests {@link AnnotationProcessorsLogger} */
public class AnnotationProcessorsLoggerTest {

  public static final String BUILD_TARGET_1 = "//testing/com/udinic/mars/rover:perseverance";
  public static final String BUILD_TARGET_2 = "//testing/com/udinic/mars/rover:curiosity";
  public static final String PROCESSOR_1 = "com.udinic.IngenuityProcessor";
  public static final String PROCESSOR_2 = "com.udinic.RadiationProcessor";

  AnnotationProcessorsLogger logger;
  BuildId buildId;

  @Before
  public void setUp() {
    buildId = new BuildId();
    logger = new AnnotationProcessorsLogger();
  }

  @Test
  public void testHandleStartedAndFinishedEvents() {
    AnnotationProcessingEvent.Started eventStarted = createStartEvent(Operation.INIT, 0, false);
    assertEmpty(logger.handleEvent(eventStarted));

    AnnotationProcessingEvent.Finished eventFinished = createFinishedEvent(eventStarted, 5000);
    assertEmpty(logger.handleEvent(eventFinished));
  }

  @Test
  public void testHandleFinishedEventWithoutStartedEvent() {
    AnnotationProcessingEvent.Started eventStarted = createStartEvent(Operation.INIT, 0, false);
    AnnotationProcessingEvent.Finished eventFinished = createFinishedEvent(eventStarted, 5000);

    // Only sending the finished event
    assertEmpty(logger.handleEvent(eventFinished));
  }

  @Test
  public void testProcessRounds() {
    final long PROCESS_RND1_DURATION = 5000;
    final long PROCESS_RND2_DURATION = 6000;
    final long PROCESS_RND3_DURATION = 7000;

    AnnotationProcessingEvent.Started startRound1 = createStartEvent(Operation.PROCESS, 0, false);
    assertEmpty(logger.handleEvent(startRound1));
    assertEmpty(logger.handleEvent(createFinishedEvent(startRound1, PROCESS_RND1_DURATION)));

    AnnotationProcessingEvent.Started startRound2 = createStartEvent(Operation.PROCESS, 1, false);
    assertEmpty(logger.handleEvent(startRound2));
    assertEmpty(logger.handleEvent(createFinishedEvent(startRound2, PROCESS_RND2_DURATION)));

    AnnotationProcessingEvent.Started startRound3 = createStartEvent(Operation.PROCESS, 2, true);
    assertEmpty(logger.handleEvent(startRound3));

    Optional<AnnotationProcessorPerfStats> annotationProcessorPerfStatsOptional =
        logger.handleEvent(createFinishedEvent(startRound3, PROCESS_RND3_DURATION));
    assertNotEmpty(annotationProcessorPerfStatsOptional);

    AnnotationProcessorPerfStats annotationProcessorPerfStats =
        annotationProcessorPerfStatsOptional.get();

    assertNotNull(annotationProcessorPerfStats);
    assertEquals(3, annotationProcessorPerfStats.getRounds());
    assertEquals(
        PROCESS_RND1_DURATION + PROCESS_RND2_DURATION + PROCESS_RND3_DURATION,
        annotationProcessorPerfStats.getTotalTime());
    assertEquals(
        ImmutableList.of(PROCESS_RND1_DURATION, PROCESS_RND2_DURATION, PROCESS_RND3_DURATION),
        annotationProcessorPerfStats.getRoundTimes());
    assertEquals(PROCESSOR_1, annotationProcessorPerfStats.getProcessorName());
    assertEquals(0, annotationProcessorPerfStats.getInitTime());
  }

  @Test
  public void testInitAndProcessing() {
    final long INIT_DURATION = 400;
    final long PROCESS_RND1_DURATION = 5000;
    final long PROCESS_RND2_DURATION = 6000;
    final long PROCESS_RND3_DURATION = 7000;

    AnnotationProcessingEvent.Started initEvent = createStartEvent(Operation.INIT, 0, false);
    assertEmpty(logger.handleEvent(initEvent));
    assertEmpty(logger.handleEvent(createFinishedEvent(initEvent, INIT_DURATION)));

    AnnotationProcessingEvent.Started startRound1 = createStartEvent(Operation.PROCESS, 0, false);
    assertEmpty(logger.handleEvent(startRound1));
    assertEmpty(logger.handleEvent(createFinishedEvent(startRound1, PROCESS_RND1_DURATION)));

    AnnotationProcessingEvent.Started startRound2 = createStartEvent(Operation.PROCESS, 1, false);
    assertEmpty(logger.handleEvent(startRound2));
    assertEmpty(logger.handleEvent(createFinishedEvent(startRound2, PROCESS_RND2_DURATION)));

    AnnotationProcessingEvent.Started startRound3 = createStartEvent(Operation.PROCESS, 2, true);
    assertEmpty(logger.handleEvent(startRound3));

    Optional<AnnotationProcessorPerfStats> annotationProcessorPerfStatsOptional =
        logger.handleEvent(createFinishedEvent(startRound3, PROCESS_RND3_DURATION));
    assertNotEmpty(annotationProcessorPerfStatsOptional);

    AnnotationProcessorPerfStats annotationProcessorPerfStats =
        annotationProcessorPerfStatsOptional.get();

    assertNotNull(annotationProcessorPerfStats);
    assertEquals(INIT_DURATION, annotationProcessorPerfStats.getInitTime());

    assertEquals(
        INIT_DURATION + PROCESS_RND1_DURATION + PROCESS_RND2_DURATION + PROCESS_RND3_DURATION,
        annotationProcessorPerfStats.getTotalTime());

    assertEquals(
        ImmutableList.of(PROCESS_RND1_DURATION, PROCESS_RND2_DURATION, PROCESS_RND3_DURATION),
        annotationProcessorPerfStats.getRoundTimes());

    assertEquals(3, annotationProcessorPerfStats.getRounds());
    assertEquals(PROCESSOR_1, annotationProcessorPerfStats.getProcessorName());
  }

  @Test
  public void testFullProcessing() {
    final long GET_SUPPORTED_OPTIONS_DURATION = 10;
    final long GET_SUPPORTED_ANNOTATION_TYPES_DURATION = 20;
    final long GET_SUPPORTED_SOURCE_VERSION_DURATION = 30;
    final long INIT_DURATION = 400;
    final long PROCESS_RND1_DURATION = 5000;
    final long PROCESS_RND2_DURATION = 6000;
    final long PROCESS_RND3_DURATION = 7000;

    handleOperation(Operation.GET_SUPPORTED_OPTIONS, GET_SUPPORTED_OPTIONS_DURATION);
    handleOperation(
        Operation.GET_SUPPORTED_ANNOTATION_TYPES, GET_SUPPORTED_ANNOTATION_TYPES_DURATION);
    handleOperation(Operation.GET_SUPPORTED_SOURCE_VERSION, GET_SUPPORTED_SOURCE_VERSION_DURATION);
    handleOperation(Operation.INIT, INIT_DURATION);
    handleOperation(Operation.PROCESS, PROCESS_RND1_DURATION, 0, false);
    handleOperation(Operation.PROCESS, PROCESS_RND2_DURATION, 1, false);
    AnnotationProcessorPerfStats annotationProcessorPerfStats =
        handleOperation(Operation.PROCESS, PROCESS_RND3_DURATION, 2, true);

    // Init time includes all other non-processing operations
    // (that's how the Kapt plugin calculates that in Kotlin)
    long expectedInitTime =
        INIT_DURATION
            + GET_SUPPORTED_ANNOTATION_TYPES_DURATION
            + GET_SUPPORTED_OPTIONS_DURATION
            + GET_SUPPORTED_SOURCE_VERSION_DURATION;

    assertEquals(expectedInitTime, annotationProcessorPerfStats.getInitTime());

    assertEquals(
        expectedInitTime + PROCESS_RND1_DURATION + PROCESS_RND2_DURATION + PROCESS_RND3_DURATION,
        annotationProcessorPerfStats.getTotalTime());

    assertEquals(
        ImmutableList.of(PROCESS_RND1_DURATION, PROCESS_RND2_DURATION, PROCESS_RND3_DURATION),
        annotationProcessorPerfStats.getRoundTimes());

    assertEquals(3, annotationProcessorPerfStats.getRounds());
    assertEquals(PROCESSOR_1, annotationProcessorPerfStats.getProcessorName());
  }

  @Test
  public void testMultipleProcessors() {
    final long PROC1_INIT_DURATION = 400;
    final long PROC1_PROCESS_RND1_DURATION = 5000;
    final long PROC1_PROCESS_RND2_DURATION = 6000;

    final long PROC2_INIT_DURATION = 300;
    final long PROC2_PROCESS_RND1_DURATION = 2000;
    final long PROC2_PROCESS_RND2_DURATION = 8000;
    final long PROC2_PROCESS_RND3_DURATION = 650;

    handleOperation(BUILD_TARGET_1, PROCESSOR_1, Operation.INIT, PROC1_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, PROC1_PROCESS_RND1_DURATION, 0, false);

    handleOperation(BUILD_TARGET_1, PROCESSOR_2, Operation.INIT, PROC2_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND1_DURATION, 0, false);
    handleOperation(
        BUILD_TARGET_1, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND2_DURATION, 1, false);

    AnnotationProcessorPerfStats statsProcessor1 =
        handleOperation(
            BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, PROC1_PROCESS_RND2_DURATION, 1, true);

    AnnotationProcessorPerfStats statsProcessor2 =
        handleOperation(
            BUILD_TARGET_1, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND3_DURATION, 2, true);

    assertPerfStats(
        statsProcessor1,
        PROCESSOR_1,
        2,
        PROC1_INIT_DURATION,
        PROC1_INIT_DURATION + PROC1_PROCESS_RND1_DURATION + PROC1_PROCESS_RND2_DURATION,
        ImmutableList.of(PROC1_PROCESS_RND1_DURATION, PROC1_PROCESS_RND2_DURATION));

    assertPerfStats(
        statsProcessor2,
        PROCESSOR_2,
        3,
        PROC2_INIT_DURATION,
        PROC2_INIT_DURATION
            + PROC2_PROCESS_RND1_DURATION
            + PROC2_PROCESS_RND2_DURATION
            + PROC2_PROCESS_RND3_DURATION,
        ImmutableList.of(
            PROC2_PROCESS_RND1_DURATION, PROC2_PROCESS_RND2_DURATION, PROC2_PROCESS_RND3_DURATION));
  }

  @Test
  public void testMultipleTargetsDifferentProcessors() {
    final long PROC1_INIT_DURATION = 400;
    final long PROC1_PROCESS_RND1_DURATION = 5000;
    final long PROC1_PROCESS_RND2_DURATION = 6000;

    final long PROC2_INIT_DURATION = 300;
    final long PROC2_PROCESS_RND1_DURATION = 2000;
    final long PROC2_PROCESS_RND2_DURATION = 8000;
    final long PROC2_PROCESS_RND3_DURATION = 650;

    handleOperation(BUILD_TARGET_1, PROCESSOR_1, Operation.INIT, PROC1_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, PROC1_PROCESS_RND1_DURATION, 0, false);

    handleOperation(BUILD_TARGET_2, PROCESSOR_2, Operation.INIT, PROC2_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_2, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND1_DURATION, 0, false);
    handleOperation(
        BUILD_TARGET_2, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND2_DURATION, 1, false);

    AnnotationProcessorPerfStats statsProcessor1 =
        handleOperation(
            BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, PROC1_PROCESS_RND2_DURATION, 1, true);

    AnnotationProcessorPerfStats statsProcessor2 =
        handleOperation(
            BUILD_TARGET_2, PROCESSOR_2, Operation.PROCESS, PROC2_PROCESS_RND3_DURATION, 2, true);

    assertPerfStats(
        statsProcessor1,
        PROCESSOR_1,
        2,
        PROC1_INIT_DURATION,
        PROC1_INIT_DURATION + PROC1_PROCESS_RND1_DURATION + PROC1_PROCESS_RND2_DURATION,
        ImmutableList.of(PROC1_PROCESS_RND1_DURATION, PROC1_PROCESS_RND2_DURATION));

    assertPerfStats(
        statsProcessor2,
        PROCESSOR_2,
        3,
        PROC2_INIT_DURATION,
        PROC2_INIT_DURATION
            + PROC2_PROCESS_RND1_DURATION
            + PROC2_PROCESS_RND2_DURATION
            + PROC2_PROCESS_RND3_DURATION,
        ImmutableList.of(
            PROC2_PROCESS_RND1_DURATION, PROC2_PROCESS_RND2_DURATION, PROC2_PROCESS_RND3_DURATION));
  }

  @Test
  public void testMultipleTargetsSameProcessor() {
    final long TARGET1_INIT_DURATION = 400;
    final long TARGET1_PROCESS_RND1_DURATION = 5000;
    final long TARGET1_PROCESS_RND2_DURATION = 6000;

    final long TARGET2_INIT_DURATION = 300;
    final long TARGET2_PROCESS_RND1_DURATION = 2000;
    final long TARGET2_PROCESS_RND2_DURATION = 8000;
    final long TARGET2_PROCESS_RND3_DURATION = 650;

    handleOperation(BUILD_TARGET_1, PROCESSOR_1, Operation.INIT, TARGET1_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, TARGET1_PROCESS_RND1_DURATION, 0, false);

    handleOperation(BUILD_TARGET_2, PROCESSOR_1, Operation.INIT, TARGET2_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_2, PROCESSOR_1, Operation.PROCESS, TARGET2_PROCESS_RND1_DURATION, 0, false);
    handleOperation(
        BUILD_TARGET_2, PROCESSOR_1, Operation.PROCESS, TARGET2_PROCESS_RND2_DURATION, 1, false);

    AnnotationProcessorPerfStats statsProcessor1 =
        handleOperation(
            BUILD_TARGET_1, PROCESSOR_1, Operation.PROCESS, TARGET1_PROCESS_RND2_DURATION, 1, true);

    AnnotationProcessorPerfStats statsProcessor2 =
        handleOperation(
            BUILD_TARGET_2, PROCESSOR_1, Operation.PROCESS, TARGET2_PROCESS_RND3_DURATION, 2, true);

    assertPerfStats(
        statsProcessor1,
        PROCESSOR_1,
        2,
        TARGET1_INIT_DURATION,
        TARGET1_INIT_DURATION + TARGET1_PROCESS_RND1_DURATION + TARGET1_PROCESS_RND2_DURATION,
        ImmutableList.of(TARGET1_PROCESS_RND1_DURATION, TARGET1_PROCESS_RND2_DURATION));

    assertPerfStats(
        statsProcessor2,
        PROCESSOR_1,
        3,
        TARGET2_INIT_DURATION,
        TARGET2_INIT_DURATION
            + TARGET2_PROCESS_RND1_DURATION
            + TARGET2_PROCESS_RND2_DURATION
            + TARGET2_PROCESS_RND3_DURATION,
        ImmutableList.of(
            TARGET2_PROCESS_RND1_DURATION,
            TARGET2_PROCESS_RND2_DURATION,
            TARGET2_PROCESS_RND3_DURATION));
  }

  @Test
  public void testMultipleTargetMultipleProcessors() {
    final long TARGET1_PROC1_INIT_DURATION = 400;
    final long TARGET1_PROC1_PROCESS_RND1_DURATION = 5000;
    final long TARGET1_PROC1_PROCESS_RND2_DURATION = 6000;

    final long TARGET1_PROC2_INIT_DURATION = 300;
    final long TARGET1_PROC2_PROCESS_RND1_DURATION = 2000;
    final long TARGET1_PROC2_PROCESS_RND2_DURATION = 8000;
    final long TARGET1_PROC2_PROCESS_RND3_DURATION = 650;

    final long TARGET2_PROC1_INIT_DURATION = 30;
    final long TARGET2_PROC1_PROCESS_RND1_DURATION = 960;

    final long TARGET2_PROC2_INIT_DURATION = 70;
    final long TARGET2_PROC2_PROCESS_RND1_DURATION = 4300;
    final long TARGET2_PROC2_PROCESS_RND2_DURATION = 672;

    handleOperation(BUILD_TARGET_1, PROCESSOR_1, Operation.INIT, TARGET1_PROC1_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1,
        PROCESSOR_1,
        Operation.PROCESS,
        TARGET1_PROC1_PROCESS_RND1_DURATION,
        0,
        false);

    handleOperation(BUILD_TARGET_1, PROCESSOR_2, Operation.INIT, TARGET1_PROC2_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_1,
        PROCESSOR_2,
        Operation.PROCESS,
        TARGET1_PROC2_PROCESS_RND1_DURATION,
        0,
        false);
    handleOperation(
        BUILD_TARGET_1,
        PROCESSOR_2,
        Operation.PROCESS,
        TARGET1_PROC2_PROCESS_RND2_DURATION,
        1,
        false);

    handleOperation(BUILD_TARGET_2, PROCESSOR_1, Operation.INIT, TARGET2_PROC1_INIT_DURATION);

    handleOperation(BUILD_TARGET_2, PROCESSOR_2, Operation.INIT, TARGET2_PROC2_INIT_DURATION);
    handleOperation(
        BUILD_TARGET_2,
        PROCESSOR_2,
        Operation.PROCESS,
        TARGET2_PROC2_PROCESS_RND1_DURATION,
        0,
        false);

    AnnotationProcessorPerfStats statsTarget1Processor1 =
        handleOperation(
            BUILD_TARGET_1,
            PROCESSOR_1,
            Operation.PROCESS,
            TARGET1_PROC1_PROCESS_RND2_DURATION,
            1,
            true);

    AnnotationProcessorPerfStats statsTarget1Processor2 =
        handleOperation(
            BUILD_TARGET_1,
            PROCESSOR_2,
            Operation.PROCESS,
            TARGET1_PROC2_PROCESS_RND3_DURATION,
            2,
            true);

    AnnotationProcessorPerfStats statsTarget2Processor1 =
        handleOperation(
            BUILD_TARGET_2,
            PROCESSOR_1,
            Operation.PROCESS,
            TARGET2_PROC1_PROCESS_RND1_DURATION,
            0,
            true);

    AnnotationProcessorPerfStats statsTarget2Processor2 =
        handleOperation(
            BUILD_TARGET_2,
            PROCESSOR_2,
            Operation.PROCESS,
            TARGET2_PROC2_PROCESS_RND2_DURATION,
            1,
            true);

    assertPerfStats(
        statsTarget1Processor1,
        PROCESSOR_1,
        2,
        TARGET1_PROC1_INIT_DURATION,
        TARGET1_PROC1_INIT_DURATION
            + TARGET1_PROC1_PROCESS_RND1_DURATION
            + TARGET1_PROC1_PROCESS_RND2_DURATION,
        ImmutableList.of(TARGET1_PROC1_PROCESS_RND1_DURATION, TARGET1_PROC1_PROCESS_RND2_DURATION));

    assertPerfStats(
        statsTarget1Processor2,
        PROCESSOR_2,
        3,
        TARGET1_PROC2_INIT_DURATION,
        TARGET1_PROC2_INIT_DURATION
            + TARGET1_PROC2_PROCESS_RND1_DURATION
            + TARGET1_PROC2_PROCESS_RND2_DURATION
            + TARGET1_PROC2_PROCESS_RND3_DURATION,
        ImmutableList.of(
            TARGET1_PROC2_PROCESS_RND1_DURATION,
            TARGET1_PROC2_PROCESS_RND2_DURATION,
            TARGET1_PROC2_PROCESS_RND3_DURATION));

    assertPerfStats(
        statsTarget2Processor1,
        PROCESSOR_1,
        1,
        TARGET2_PROC1_INIT_DURATION,
        TARGET2_PROC1_INIT_DURATION + TARGET2_PROC1_PROCESS_RND1_DURATION,
        ImmutableList.of(TARGET2_PROC1_PROCESS_RND1_DURATION));

    assertPerfStats(
        statsTarget2Processor2,
        PROCESSOR_2,
        2,
        TARGET2_PROC2_INIT_DURATION,
        TARGET2_PROC2_INIT_DURATION
            + TARGET2_PROC2_PROCESS_RND1_DURATION
            + TARGET2_PROC2_PROCESS_RND2_DURATION,
        ImmutableList.of(TARGET2_PROC2_PROCESS_RND1_DURATION, TARGET2_PROC2_PROCESS_RND2_DURATION));
  }

  @Test
  public void testCleanInternalStatsObjectAfterLastRound() {
    final long INIT_DURATION = 400;
    final long PROCESS_RND1_DURATION = 5000;
    final long PROCESS_RND2_DURATION = 6000;

    final long INIT_DURATION_2 = 30;
    final long PROCESS_RND1_DURATION_2 = 900;

    handleOperation(Operation.INIT, INIT_DURATION);
    handleOperation(Operation.PROCESS, PROCESS_RND1_DURATION, 0, false);

    AnnotationProcessorPerfStats stats1 =
        handleOperation(Operation.PROCESS, PROCESS_RND2_DURATION, 1, true);

    assertPerfStats(
        stats1,
        PROCESSOR_1,
        2,
        INIT_DURATION,
        INIT_DURATION + PROCESS_RND1_DURATION + PROCESS_RND2_DURATION,
        ImmutableList.of(PROCESS_RND1_DURATION, PROCESS_RND2_DURATION));

    // New data for the same processor
    handleOperation(Operation.INIT, INIT_DURATION_2);
    AnnotationProcessorPerfStats stats2 =
        handleOperation(Operation.PROCESS, PROCESS_RND1_DURATION_2, 0, true);

    // Verify the new stats object doesn't contain data from the previous run
    assertPerfStats(
        stats2,
        PROCESSOR_1,
        1,
        INIT_DURATION_2,
        INIT_DURATION_2 + PROCESS_RND1_DURATION_2,
        ImmutableList.of(PROCESS_RND1_DURATION_2));
  }

  private void assertPerfStats(
      AnnotationProcessorPerfStats perfStatsData,
      String processorName,
      int rounds,
      long initTime,
      long totalTime,
      List<Long> roundTimes) {
    assertNotNull(perfStatsData);
    assertEquals(processorName, perfStatsData.getProcessorName());
    assertEquals(rounds, perfStatsData.getRounds());
    assertEquals(initTime, perfStatsData.getInitTime());
    assertEquals(totalTime, perfStatsData.getTotalTime());
    assertEquals(roundTimes, perfStatsData.getRoundTimes());
  }

  private void assertEmpty(Optional<?> optional) {
    if (optional.isPresent()) {
      fail();
    }
  }

  private void assertNotEmpty(Optional<?> optional) {
    if (!optional.isPresent()) {
      fail();
    }
  }

  private AnnotationProcessorPerfStats handleOperation(Operation operation, long duration) {
    return handleOperation(operation, duration, 0, false);
  }

  private AnnotationProcessorPerfStats handleOperation(
      String buildTargetName, String annotationProcessorName, Operation operation, long duration) {
    return handleOperation(buildTargetName, annotationProcessorName, operation, duration, 0, false);
  }

  private AnnotationProcessorPerfStats handleOperation(
      Operation operation, long duration, int round, boolean isLastRound) {
    return handleOperation(BUILD_TARGET_1, PROCESSOR_1, operation, duration, round, isLastRound);
  }

  private AnnotationProcessorPerfStats handleOperation(
      String buildTargetName,
      String annotationProcessorName,
      Operation operation,
      long duration,
      int round,
      boolean isLastRound) {
    AnnotationProcessingEvent.Started startEvent =
        createStartEvent(buildTargetName, annotationProcessorName, operation, round, isLastRound);
    assertEmpty(logger.handleEvent(startEvent));

    Optional<AnnotationProcessorPerfStats> annotationProcessorPerfStatsOptional =
        logger.handleEvent(createFinishedEvent(startEvent, duration));

    if (isLastRound) {
      assertNotEmpty(annotationProcessorPerfStatsOptional);
      return annotationProcessorPerfStatsOptional.get();
    } else {
      assertEmpty(annotationProcessorPerfStatsOptional);
      return null;
    }
  }

  private AnnotationProcessingEvent.Finished createFinishedEvent(
      AnnotationProcessingEvent.Started eventStarted, long timeMs) {
    AnnotationProcessingEvent.Finished eventFinished =
        new AnnotationProcessingEvent.Finished(eventStarted);

    eventFinished.configure(
        currentTimeMillis(), TimeUnit.MILLISECONDS.toNanos(timeMs), -1, 1, buildId);
    return eventFinished;
  }

  private AnnotationProcessingEvent.Started createStartEvent(
      Operation operation, int round, boolean isLastRound) {
    return createStartEvent(BUILD_TARGET_1, PROCESSOR_1, operation, round, isLastRound);
  }

  private AnnotationProcessingEvent.Started createStartEvent(
      String buildTargetName,
      String annotationProcessorName,
      Operation operation,
      int round,
      boolean isLastRound) {
    AnnotationProcessingEvent.Started eventStarted =
        new AnnotationProcessingEvent.Started(
            buildTargetName, annotationProcessorName, operation, round, isLastRound);

    eventStarted.configure(currentTimeMillis(), 0, 0, 1, buildId);

    return eventStarted;
  }
}
