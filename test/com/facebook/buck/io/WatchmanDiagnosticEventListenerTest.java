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

package com.facebook.buck.io;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.PredicateMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WatchmanDiagnosticEventListenerTest {

  private final SnoopingListener snoopingListener = new SnoopingListener();
  private final BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

  @Before
  public void setUp() {
    buckEventBus.register(snoopingListener);
    buckEventBus.register(new WatchmanDiagnosticEventListener(buckEventBus));
  }

  @Test
  public void sendsEventsToEventBus() {
    WatchmanDiagnostic warningDiagnostic =
        WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, "a warning");
    WatchmanDiagnostic errorDiagnostic =
        WatchmanDiagnostic.of(WatchmanDiagnostic.Level.ERROR, "an error");

    buckEventBus.post(new WatchmanDiagnosticEvent(warningDiagnostic));
    buckEventBus.post(new WatchmanDiagnosticEvent(errorDiagnostic));

    Assert.assertThat(
        snoopingListener.receivedEvents,
        Matchers.contains(
            ImmutableList.of(
                new PredicateMatcher<>(
                    "warning message containing \"a warning\"",
                    (ConsoleEvent event) ->
                        event.getMessage().contains("a warning")
                            && event.getLevel() == Level.WARNING),
                new PredicateMatcher<>(
                    "error message containing \"an error\"",
                    (ConsoleEvent event) ->
                        event.getMessage().contains("an error")
                            && event.getLevel() == Level.SEVERE))));
  }

  @Test
  public void deduplicatesEvents() {
    WatchmanDiagnostic diagnostic =
        WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, "a warning");
    WatchmanDiagnostic equalDiagnostic =
        WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, "a warning");
    WatchmanDiagnostic unequalDiagnostic =
        WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, "another warning");

    buckEventBus.post(new WatchmanDiagnosticEvent(diagnostic));
    buckEventBus.post(new WatchmanDiagnosticEvent(equalDiagnostic));
    buckEventBus.post(new WatchmanDiagnosticEvent(unequalDiagnostic));

    Assert.assertThat(
        snoopingListener.receivedEvents,
        Matchers.contains(
            ImmutableList.of(
                new PredicateMatcher<>(
                    "message containing \"a warning\"",
                    (ConsoleEvent event) -> event.getMessage().contains("a warning")),
                new PredicateMatcher<>(
                    "message containing \"another warning\"",
                    (ConsoleEvent event) -> event.getMessage().contains("another warning")))));
  }

  /** A listener that receives and records console events. */
  private static class SnoopingListener implements BuckEventListener {
    List<ConsoleEvent> receivedEvents = new ArrayList<>();

    @Subscribe
    public void on(ConsoleEvent event) {
      receivedEvents.add(event);
    }

    @Override
    public void outputTrace(BuildId buildId) {}
  }
}
