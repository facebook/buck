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

package com.facebook.buck.jvm.kotlin;

import static com.facebook.buck.javacd.model.BuildTargetValue.Type.LIBRARY;
import static com.google.common.collect.Lists.newArrayList;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verifyUnexpectedCalls;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.AnnotationProcessorPerfStats;
import com.facebook.buck.event.AnnotationProcessorStatsEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/** Tests {@link KaptStatsReportParseStep} */
public class KaptStatsReportParseStepTest {

  private KaptStatsReportParseStep step;
  private Capture<AnnotationProcessorStatsEvent> captureStatsEvent;
  private BuckEventBus mockEventBus;

  @Before
  public void setUp() {
    Path dummyPath = TestDataHelper.getTestDataDirectory(this);

    BuildTargetValue dummyBuildTarget = BuildTargetValue.of(LIBRARY, "//udinic/test:test");

    mockEventBus = EasyMock.createMock(BuckEventBus.class);
    captureStatsEvent = EasyMock.newCapture(CaptureType.ALL);

    step = new KaptStatsReportParseStep(dummyPath, dummyBuildTarget, mockEventBus);
  }

  @Test
  public void testOneProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.udinic.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms");

    mockEventBus.post(capture(captureStatsEvent));
    EasyMock.expectLastCall().once();
    replay(mockEventBus);

    step.parseReport(reportLines);

    AnnotationProcessorPerfStats processorStats = captureStatsEvent.getValue().getData();

    assertProcessorStats(
        processorStats, "com.udinic.BlaProcessor", 15, 999, 4, newArrayList(570L, 200L, 30L, 184L));
  }

  @Test
  public void testMultipleProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.udinic.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms",
            "com.udinic.CoolProcessor: total: 314 ms, init: 1 ms, 4 round(s): 313 ms, 0 ms, 0 ms, 0 ms",
            "com.udinic.UdinicProcessor: total: 38 ms, init: 19 ms, 4 round(s): 16 ms, 2 ms, 1 ms, 0 ms");

    mockEventBus.post(capture(captureStatsEvent));
    EasyMock.expectLastCall().times(3);
    replay(mockEventBus);

    step.parseReport(reportLines);

    List<AnnotationProcessorPerfStats> processorStatsList =
        captureStatsEvent.getValues().stream()
            .map(AnnotationProcessorStatsEvent::getData)
            .collect(Collectors.toList());

    assertEquals(3, processorStatsList.size());

    assertProcessorStats(
        processorStatsList.get(0),
        "com.udinic.BlaProcessor",
        15,
        999,
        4,
        newArrayList(570L, 200L, 30L, 184L));
    assertProcessorStats(
        processorStatsList.get(1),
        "com.udinic.CoolProcessor",
        1,
        314,
        4,
        newArrayList(313L, 0L, 0L, 0L));
    assertProcessorStats(
        processorStatsList.get(2),
        "com.udinic.UdinicProcessor",
        19,
        38,
        4,
        newArrayList(16L, 2L, 1L, 0L));
  }

  @Test
  public void testProcessorNoRounds() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.udinic.SadProcessor: total: 0 ms, init: 0 ms, 0 round(s): ");

    mockEventBus.post(capture(captureStatsEvent));
    EasyMock.expectLastCall().once();
    replay(mockEventBus);

    step.parseReport(reportLines);

    AnnotationProcessorPerfStats processorStats = captureStatsEvent.getValue().getData();

    assertProcessorStats(processorStats, "com.udinic.SadProcessor", 0, 0, 0, newArrayList());
  }

  @Test
  public void testEmptyReport() {
    List<String> reportLines = newArrayList("Kapt Annotation Processing performance report:");

    replay(mockEventBus);

    step.parseReport(reportLines);

    // There should be no calls to the event bus
    verifyUnexpectedCalls(mockEventBus);
  }

  @Test
  public void testEmptyFile() {
    List<String> reportLines = newArrayList();

    replay(mockEventBus);

    step.parseReport(reportLines);

    // There should be no calls to the event bus
    verifyUnexpectedCalls(mockEventBus);
  }

  @Test
  public void testSubclassProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.udinic.MasterClass$SubProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms");

    mockEventBus.post(capture(captureStatsEvent));
    EasyMock.expectLastCall().once();
    replay(mockEventBus);

    step.parseReport(reportLines);

    AnnotationProcessorPerfStats processorStats = captureStatsEvent.getValue().getData();

    // Making sure the parser accounts for the "$" sign in the class name
    assertProcessorStats(
        processorStats,
        "com.udinic.MasterClass$SubProcessor",
        15,
        999,
        4,
        newArrayList(570L, 200L, 30L, 184L));
  }

  private void assertProcessorStats(
      AnnotationProcessorPerfStats processorStats,
      String processorName,
      long initTime,
      long totalTime,
      int numRounds,
      List<Long> roundTimes) {
    assertEquals(processorStats.getProcessorName(), processorName);
    assertEquals(processorStats.getInitTime(), initTime);
    assertEquals(processorStats.getTotalTime(), totalTime);
    assertEquals(processorStats.getRounds(), numRounds);
    assertEquals(processorStats.getRoundTimes(), roundTimes);
  }
}
