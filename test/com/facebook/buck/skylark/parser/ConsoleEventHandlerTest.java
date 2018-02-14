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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.HashSet;
import java.util.logging.Level;
import org.junit.Assert;
import org.junit.Test;

public class ConsoleEventHandlerTest {
  @Test
  public void postsAtCorrectLevels() {
    FakeBuckEventListener listener = new FakeBuckEventListener();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(listener);

    ImmutableList.Builder<Event> toSendBuilder = ImmutableList.builder();
    ImmutableList.Builder<ConsoleEvent> expectedEventsBuilder = ImmutableList.builder();

    expectedEventsBuilder.add(
        ConsoleEvent.warning("WARNING: foo/bar:1:1: Message at level WARNING"),
        ConsoleEvent.info("STDERR: foo/bar:2:1: Message at level STDERR"),
        ConsoleEvent.info("INFO: foo/bar:3:1: Message at level INFO"),
        ConsoleEvent.severe("PROGRESS: foo/bar:4:1: Message at level PROGRESS"),
        ConsoleEvent.severe("START: foo/bar:5:1: Message at level START"),
        ConsoleEvent.severe("FINISH: foo/bar:6:1: Message at level FINISH"),
        ConsoleEvent.severe("SUBCOMMAND: foo/bar:7:1: Message at level SUBCOMMAND"),
        ConsoleEvent.severe("FAIL: foo/bar:8:1: Message at level FAIL"),
        ConsoleEvent.severe("TIMEOUT: foo/bar:9:1: Message at level TIMEOUT"),
        ConsoleEvent.severe("DEPCHECKER: foo/bar:10:1: Message at level DEPCHECKER"),
        ConsoleEvent.severe("ERROR: foo/bar:11:1: Message at level ERROR"),
        ConsoleEvent.info("DEBUG: foo/bar:12:1: Message at level DEBUG"),
        ConsoleEvent.info("PASS: foo/bar:13:1: Message at level PASS"),
        ConsoleEvent.info("STDOUT: foo/bar:14:1: Message at level STDOUT"),
        ConsoleEvent.severe("ERROR: No location given"));

    toSendBuilder.add(
        Event.of(
            EventKind.WARNING,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(1, 1)),
            "Message at level WARNING"),
        Event.of(
            EventKind.STDERR,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(2, 1)),
            "Message at level STDERR"),
        Event.of(
            EventKind.INFO,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(3, 1)),
            "Message at level INFO"),
        Event.of(
            EventKind.PROGRESS,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(4, 1)),
            "Message at level PROGRESS"),
        Event.of(
            EventKind.START,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(5, 1)),
            "Message at level START"),
        Event.of(
            EventKind.FINISH,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(6, 1)),
            "Message at level FINISH"),
        Event.of(
            EventKind.SUBCOMMAND,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(7, 1)),
            "Message at level SUBCOMMAND"),
        Event.of(
            EventKind.FAIL,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(8, 1)),
            "Message at level FAIL"),
        Event.of(
            EventKind.TIMEOUT,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(9, 1)),
            "Message at level TIMEOUT"),
        Event.of(
            EventKind.DEPCHECKER,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(10, 1)),
            "Message at level DEPCHECKER"),
        Event.of(
            EventKind.ERROR,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(11, 1)),
            "Message at level ERROR"),
        Event.of(
            EventKind.DEBUG,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(12, 1)),
            "Message at level DEBUG"),
        Event.of(
            EventKind.PASS,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(13, 1)),
            "Message at level PASS"),
        Event.of(
            EventKind.STDOUT,
            Location.fromPathAndStartColumn(
                PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(14, 1)),
            "Message at level STDOUT"),
        Event.of(EventKind.ERROR, null, "No location given"));

    ImmutableList<Event> toSend = toSendBuilder.build();
    ImmutableList<ConsoleEvent> expectedEvents = expectedEventsBuilder.build();

    ConsoleEventHandler eventHandler = new ConsoleEventHandler(eventBus, EventKind.ALL_EVENTS);
    for (Event event : toSend) {
      eventHandler.handle(event);
    }

    Assert.assertEquals(
        expectedEvents
            .stream()
            .map(ConsoleEvent::getLevel)
            .collect(ImmutableList.toImmutableList()),
        listener
            .getEvents()
            .stream()
            .map(e -> ((ConsoleEvent) e).getLevel())
            .collect(ImmutableList.toImmutableList()));
    Assert.assertEquals(
        expectedEvents
            .stream()
            .map(ConsoleEvent::getMessage)
            .collect(ImmutableList.toImmutableList()),
        listener
            .getEvents()
            .stream()
            .map(e -> ((ConsoleEvent) e).getMessage())
            .collect(ImmutableList.toImmutableList()));
  }

  @Test
  public void doesNotPostUnrequestedMessageTypes() throws IOException {
    FakeBuckEventListener listener = new FakeBuckEventListener();
    try (BuckEventBus eventBus = BuckEventBusForTests.newInstance()) {
      eventBus.register(listener);

      HashSet<EventKind> handledEvents = new HashSet<>();
      handledEvents.addAll(EventKind.ALL_EVENTS);
      handledEvents.remove(EventKind.WARNING);
      ConsoleEventHandler eventHandler = new ConsoleEventHandler(eventBus, handledEvents);
      eventHandler.handle(
          Event.of(
              EventKind.WARNING,
              Location.fromPathAndStartColumn(
                  PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(1, 1)),
              "Message at level WARNING"));
      eventHandler.handle(
          Event.of(
              EventKind.ERROR,
              Location.fromPathAndStartColumn(
                  PathFragment.create("foo/bar"), 0, 100, new Location.LineAndColumn(2, 1)),
              "Testing"));
    }

    Assert.assertEquals(
        ImmutableList.of("ERROR: foo/bar:2:1: Testing"),
        listener
            .getEvents()
            .stream()
            .map(e -> ((ConsoleEvent) e).getMessage())
            .collect(ImmutableList.toImmutableList()));
    Assert.assertEquals(
        ImmutableList.of(Level.SEVERE),
        listener
            .getEvents()
            .stream()
            .map(e -> ((ConsoleEvent) e).getLevel())
            .collect(ImmutableList.toImmutableList()));
  }
}
