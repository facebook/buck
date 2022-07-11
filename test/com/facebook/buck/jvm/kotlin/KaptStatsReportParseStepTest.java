/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static com.facebook.buck.cd.model.java.BuildTargetValue.Type.LIBRARY;
import static com.google.common.collect.Lists.newArrayList;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verifyUnexpectedCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.event.AnnotationProcessorGenerationStats;
import com.facebook.buck.event.AnnotationProcessorPerfStats;
import com.facebook.buck.event.AnnotationProcessorStatsEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/** Tests {@link KaptStatsReportParseStep} */
public class KaptStatsReportParseStepTest {

  private KaptStatsReportParseStep step;
  private Capture<AnnotationProcessorStatsEvent> captureEvent;
  private IsolatedEventBus mockEventBus;
  private ActionId actionId = ActionId.of("test-action-id");

  @Before
  public void setUp() {
    Path dummyPath = TestDataHelper.getTestDataDirectory(this);

    BuildTargetValue dummyBuildTarget = BuildTargetValue.of(LIBRARY, "//example/test:test");

    mockEventBus = EasyMock.createMock(IsolatedEventBus.class);
    captureEvent = EasyMock.newCapture(CaptureType.ALL);

    step = new KaptStatsReportParseStep(dummyPath, dummyBuildTarget);
  }

  @Test
  public void testOneProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    assertProcessorStats(
        getCapturedStats().get("com.example.BlaProcessor"),
        "com.example.BlaProcessor",
        15,
        999,
        4,
        newArrayList(570L, 200L, 30L, 184L));
  }

  @Test
  public void testMultipleProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms",
            "com.example.CoolProcessor: total: 314 ms, init: 1 ms, 4 round(s): 313 ms, 0 ms, 0 ms, 0 ms",
            "com.example.MyProcessor: total: 38 ms, init: 19 ms, 4 round(s): 16 ms, 2 ms, 1 ms, 0 ms");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(6);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    Map<String, AnnotationProcessorPerfStats> capturedStats = getCapturedStats();

    assertEquals(3, capturedStats.size());

    assertProcessorStats(
        capturedStats.get("com.example.BlaProcessor"),
        "com.example.BlaProcessor",
        15,
        999,
        4,
        newArrayList(570L, 200L, 30L, 184L));
    assertProcessorStats(
        capturedStats.get("com.example.CoolProcessor"),
        "com.example.CoolProcessor",
        1,
        314,
        4,
        newArrayList(313L, 0L, 0L, 0L));
    assertProcessorStats(
        capturedStats.get("com.example.MyProcessor"),
        "com.example.MyProcessor",
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
            "com.example.SadProcessor: total: 0 ms, init: 0 ms, 0 round(s): ");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    assertProcessorStats(
        getCapturedStats().get("com.example.SadProcessor"),
        "com.example.SadProcessor",
        0,
        0,
        0,
        newArrayList());
  }

  @Test
  public void testSingleProcessorGenerationReport() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms",
            "Generated files report:",
            "com.example.BlaProcessor: total sources: 3, sources per round: 3, 0, 0, 0");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    AnnotationProcessorPerfStats blaStats = getCapturedStats().get("com.example.BlaProcessor");

    assertProcessorStats(
        blaStats, "com.example.BlaProcessor", 15, 999, 4, newArrayList(570L, 200L, 30L, 184L));
    assertProcessorGenerationStats(blaStats, 3, newArrayList(3L, 0L, 0L, 0L));
  }

  @Test
  public void testMultipleProcessorGenerationReport() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms",
            "com.example.CoolProcessor: total: 314 ms, init: 1 ms, 4 round(s): 313 ms, 0 ms, 0 ms, 0 ms",
            "com.example.MyProcessor: total: 38 ms, init: 19 ms, 4 round(s): 16 ms, 2 ms, 1 ms, 0 ms",
            "Generated files report:",
            "com.example.BlaProcessor: total sources: 2, sources per round: 2, 0, 0, 0",
            "com.example.CoolProcessor: total sources: 0, sources per round: 0, 0, 0, 0",
            "com.example.MyProcessor: total sources: 4, sources per round: 2, 1, 1, 0");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(6);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    Map<String, AnnotationProcessorPerfStats> capturedStats = getCapturedStats();

    assertEquals(3, capturedStats.size());

    assertProcessorStats(
        capturedStats.get("com.example.BlaProcessor"),
        "com.example.BlaProcessor",
        15,
        999,
        4,
        newArrayList(570L, 200L, 30L, 184L));
    assertProcessorGenerationStats(
        capturedStats.get("com.example.BlaProcessor"), 2, newArrayList(2L, 0L, 0L, 0L));

    assertProcessorStats(
        capturedStats.get("com.example.CoolProcessor"),
        "com.example.CoolProcessor",
        1,
        314,
        4,
        newArrayList(313L, 0L, 0L, 0L));
    assertProcessorGenerationStats(
        capturedStats.get("com.example.CoolProcessor"), 0, newArrayList(0L, 0L, 0L, 0L));

    assertProcessorStats(
        capturedStats.get("com.example.MyProcessor"),
        "com.example.MyProcessor",
        19,
        38,
        4,
        newArrayList(16L, 2L, 1L, 0L));
    assertProcessorGenerationStats(
        capturedStats.get("com.example.MyProcessor"), 4, newArrayList(2L, 1L, 1L, 0L));
  }

  @Test
  public void testNoRoundsGenerationReport() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.SadProcessor: total: 0 ms, init: 0 ms, 0 round(s): ",
            "Generated files report:",
            "com.example.SadProcessor: total sources: 0, sources per round: ");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    AnnotationProcessorPerfStats sadStats = getCapturedStats().get("com.example.SadProcessor");
    assertProcessorStats(sadStats, "com.example.SadProcessor", 0, 0, 0, newArrayList());
    assertProcessorGenerationStats(sadStats, 0, newArrayList());
  }

  @Test
  public void testErrorInGenerationReport() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.BlaProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms",
            "Generated files report:",
            "com.example.BlaProcessor: total sources: -1, sources per round: -1, -1, -1, -1");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    AnnotationProcessorPerfStats blaStats = getCapturedStats().get("com.example.BlaProcessor");
    assertProcessorStats(
        blaStats, "com.example.BlaProcessor", 15, 999, 4, newArrayList(570L, 200L, 30L, 184L));
    assertProcessorGenerationStats(blaStats, -1, newArrayList(-1L, -1L, -1L, -1L));
  }

  @Test
  public void testEmptyReport() {
    List<String> reportLines = newArrayList("Kapt Annotation Processing performance report:");

    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    // There should be no calls to the event bus
    verifyUnexpectedCalls(mockEventBus);
  }

  @Test
  public void testEmptyFile() {
    List<String> reportLines = newArrayList();

    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    // There should be no calls to the event bus
    verifyUnexpectedCalls(mockEventBus);
  }

  @Test
  public void testSubclassProcessor() {
    List<String> reportLines =
        newArrayList(
            "Kapt Annotation Processing performance report:",
            "com.example.MasterClass$SubProcessor: total: 999 ms, init: 15 ms, 4 round(s): 570 ms, 200 ms, 30 ms, 184 ms");

    mockEventBus.post(capture(captureEvent), eq(actionId));
    EasyMock.expectLastCall().times(2);
    replay(mockEventBus);

    step.parseReport(mockEventBus, actionId, reportLines);

    // Making sure the parser accounts for the "$" sign in the class name
    assertProcessorStats(
        getCapturedStats().get("com.example.MasterClass$SubProcessor"),
        "com.example.MasterClass$SubProcessor",
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

  private void assertProcessorGenerationStats(
      AnnotationProcessorPerfStats processorStats,
      long sourcesGenerated,
      List<Long> roundGeneratedSources) {
    AnnotationProcessorGenerationStats generationStats = processorStats.getGenerationStats();
    assertNotNull(generationStats);
    assertEquals(processorStats.getProcessorName(), generationStats.getProcessorName());
    assertEquals(sourcesGenerated, generationStats.getTotalSources());
    assertEquals(roundGeneratedSources, generationStats.getSourcesGenerated());
  }

  private Map<String, AnnotationProcessorPerfStats> getCapturedStats() {
    HashMap<String, AnnotationProcessorPerfStats> stats = new HashMap<>();

    for (AnnotationProcessorStatsEvent event : captureEvent.getValues()) {
      if (event instanceof AnnotationProcessorStatsEvent.Started) {
        AnnotationProcessorStatsEvent.Started startEvent =
            (AnnotationProcessorStatsEvent.Started) event;
        stats.put(startEvent.getCategory(), startEvent.getData());
      }
    }

    return stats;
  }
}
