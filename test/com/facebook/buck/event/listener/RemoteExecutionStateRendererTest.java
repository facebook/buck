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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import java.util.Locale;
import java.util.function.Function;
import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RemoteExecutionStateRendererTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private static final Ansi ANSI = Ansi.withoutTty();
  private static final Function<Long, String> FORMAT_TIME_FUNCTION =
      timeMs -> String.format(Locale.US, "%.1fs", timeMs / 1000.0);
  private static final long CURRENT_TIME_MILLIS = 3000;
  private static final int OUTPUT_MAX_COLUMNS = 80;
  private static final long MINIMUM_DURATION_MILLIS = 500;
  private static final int MAX_CONCURRENT_EXECUTIONS = 250;

  private RemoteExecutionStateRenderer testRenderer;

  @Test
  public void testGetSortedIds_NonEmptyTargets() {
    testRenderer = createTestRenderer(getEvents(createBuildTargets(4)));
    assertEquals(ImmutableList.of(0L, 1L, 2L, 3L), testRenderer.getSortedIds(/* unused= */ false));
  }

  @Test
  public void testGetSortedIds_EmptyTargets() {
    testRenderer = createTestRenderer(getEvents(createBuildTargets(0)));
    assertEquals(ImmutableList.of(), testRenderer.getSortedIds(/* unused= */ false));
  }

  @Test
  public void testTargetsGetFilteredByElapsedTime() {
    testRenderer = createTestRenderer(getEvents(createBuildTargets(9)));
    // Timestamps are 2100, 2200, 2300, 2400, 2500, 2600, 2700, 2800, 2900; last three should be
    // filtered out
    assertEquals(
        ImmutableList.of(0L, 1L, 2L, 3L, 4L), testRenderer.getSortedIds(/* unused= */ false));
  }

  @Test
  public void testRenderStatusLine() {
    int numTargets = 4;
    testRenderer = createTestRenderer(getEvents(createBuildTargets(numTargets)));

    for (int i = 0; i < numTargets; i++) {
      // e.g. [RE]  - //:target0... 0.9s
      assertEquals(
          "[RE]  - //:target" + i + "... 0." + (9 - i) + "s",
          testRenderer.renderStatusLine(Long.valueOf(i)));
    }
  }

  @Test
  public void testRenderStatusLine_ThrowsExceptionIfGivenIdIsInvalid() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Received invalid targetId.");
    testRenderer = createTestRenderer(getEvents(createBuildTargets(4)));

    testRenderer.renderStatusLine(4L);
  }

  @Test
  public void testRenderShortStatus_ThrowsExceptionIfGivenIdIsInvalid() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Received invalid targetId.");
    testRenderer = createTestRenderer(getEvents(createBuildTargets(4)));

    testRenderer.renderStatusLine(-1L);
  }

  @Test
  public void testRenderShortStatus() {
    int numTargets = 4;
    testRenderer = createTestRenderer(getEvents(createBuildTargets(numTargets)));

    for (int i = 0; i < numTargets; i++) {
      assertEquals("[RE] [.]", testRenderer.renderShortStatus(Long.valueOf(i)));
    }
  }

  private ImmutableList<BuildTargetWrapper> createBuildTargets(int numTargets) {
    ImmutableList.Builder<BuildTargetWrapper> builder = ImmutableList.builder();

    long startTimeMillis = 2100;
    for (int i = 0; i < numTargets; i++) {
      BuildTarget target = BuildTargetFactory.newInstance("//:target" + i);
      RemoteExecutionActionEvent.Started mockEvent =
          EasyMock.createMock(RemoteExecutionActionEvent.Started.class);
      EasyMock.expect(mockEvent.getTimestampMillis()).andReturn(startTimeMillis).anyTimes();
      EasyMock.expect(mockEvent.getBuildTarget()).andReturn(target).anyTimes();
      EasyMock.replay(mockEvent);
      startTimeMillis += 100L;
      builder.add(new BuildTargetWrapper(target, mockEvent));
    }

    return builder.build();
  }

  private RemoteExecutionStateRenderer createTestRenderer(
      ImmutableList<RemoteExecutionActionEvent.Started> buildTargets) {
    return new RemoteExecutionStateRenderer(
        ANSI,
        FORMAT_TIME_FUNCTION,
        CURRENT_TIME_MILLIS,
        OUTPUT_MAX_COLUMNS,
        MINIMUM_DURATION_MILLIS,
        MAX_CONCURRENT_EXECUTIONS,
        buildTargets);
  }

  private ImmutableList<RemoteExecutionActionEvent.Started> getEvents(
      ImmutableList<BuildTargetWrapper> wrappers) {
    return ImmutableList.copyOf(
        wrappers.stream().map(w -> w.event).toArray(RemoteExecutionActionEvent.Started[]::new));
  }

  private static class BuildTargetWrapper {
    private final BuildTarget target;
    private final RemoteExecutionActionEvent.Started event;

    private BuildTargetWrapper(BuildTarget target, RemoteExecutionActionEvent.Started event) {
      this.target = target;
      this.event = event;
    }
  }
}
