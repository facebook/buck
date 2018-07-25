/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;

public class CommonThreadStateRendererTest {

  private Ansi ansi;
  private Function<Long, String> timeToString;
  private ActionGraphEvent fakeEvent;

  @Before
  public void setUp() {
    ansi = new Ansi(false);
    timeToString = time -> time.toString();
    fakeEvent = ActionGraphEvent.started();
    fakeEvent.configure(0, 0, 0, 0, new BuildId());
  }

  @Test
  public void rendersShortStatusWithCorrectColor() {
    CommonThreadStateRenderer renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 80, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText("[:]"),
        renderer.renderShortStatus(
            true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS - 1));

    assertEquals(
        ansi.asWarningText("[:]"),
        renderer.renderShortStatus(true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS));
    assertEquals(
        ansi.asSubtleText("[:]"),
        renderer.renderShortStatus(true, false, CommonThreadStateRenderer.ERROR_THRESHOLD_MS));
  }

  @Test
  public void rendersShortStatusWithAnimation() {
    CommonThreadStateRenderer renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 80, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText("[:]"),
        renderer.renderShortStatus(
            true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS - 1));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false),
            time -> time.toString(),
            CommonThreadStateRenderer.ANIMATION_DURATION,
            80,
            ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText("[']"),
        renderer.renderShortStatus(
            true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS - 1));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false),
            time -> time.toString(),
            CommonThreadStateRenderer.ANIMATION_DURATION * 2,
            80,
            ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText("[:]"),
        renderer.renderShortStatus(
            true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS - 1));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false),
            time -> time.toString(),
            CommonThreadStateRenderer.ANIMATION_DURATION * 3,
            80,
            ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText("[.]"),
        renderer.renderShortStatus(
            true, false, CommonThreadStateRenderer.WARNING_THRESHOLD_MS - 1));
  }

  @Test
  public void rendersLineCorrectLength() {
    CommonThreadStateRenderer renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 100, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText(" - //some:target... 5000 (running some step[0])"),
        renderer.renderLine(
            Optional.of(BuildTargetFactory.newInstance("some:target")),
            Optional.of(fakeEvent),
            Optional.of(fakeEvent),
            Optional.of("some step"),
            Optional.empty(),
            5000,
            new StringBuilder()));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 15, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText(" - //so... 5000"),
        renderer.renderLine(
            Optional.of(BuildTargetFactory.newInstance("some:target")),
            Optional.of(fakeEvent),
            Optional.of(fakeEvent),
            Optional.empty(),
            Optional.empty(),
            5000,
            new StringBuilder()));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 45, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText(" - //some:target... 5000 (running som... [0])"),
        renderer.renderLine(
            Optional.of(BuildTargetFactory.newInstance("some:target")),
            Optional.of(fakeEvent),
            Optional.of(fakeEvent),
            Optional.of("some step"),
            Optional.empty(),
            5000,
            new StringBuilder()));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 80, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText(" - //some:target... 5000 (some placeholder)"),
        renderer.renderLine(
            Optional.of(BuildTargetFactory.newInstance("some:target")),
            Optional.of(fakeEvent),
            Optional.of(fakeEvent),
            Optional.empty(),
            Optional.of("some placeholder"),
            5000,
            new StringBuilder()));

    renderer =
        new CommonThreadStateRenderer(
            new Ansi(false), time -> time.toString(), 0, 40, ImmutableMap.of());

    assertEquals(
        ansi.asSubtleText(" - //some:target... 5000 (some plac... )"),
        renderer.renderLine(
            Optional.of(BuildTargetFactory.newInstance("some:target")),
            Optional.of(fakeEvent),
            Optional.of(fakeEvent),
            Optional.empty(),
            Optional.of("some placeholder"),
            5000,
            new StringBuilder()));
  }
}
