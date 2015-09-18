/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import org.easymock.Capture;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Set;

public class WatchmanWatcherTest {

  @After
  public void cleanUp() {
    // Clear interrupted state so it doesn't affect any other test.
    Thread.interrupted();
  }

  @Test
  public void whenFilesListIsEmptyThenNoEventsAreGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"clock\": \"c:1386170113:26390:5:50273\",",
        "\"is_fresh_instance\": false,",
        "\"files\": []",
        "}");
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
  }

  @Test
  public void whenNameThenModifyEventIsGenerated() throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertEquals("Should be modify event.",
        StandardWatchEventKinds.ENTRY_MODIFY,
        eventCapture.getValue().kind());
    assertEquals("Path should match watchman output.",
        "foo/bar/baz",
        eventCapture.getValue().context().toString());
  }

  @Test
  public void whenNewIsTrueThenCreateEventIsGenerated() throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"new\": true",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertEquals("Should be create event.",
        StandardWatchEventKinds.ENTRY_CREATE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenExistsIsFalseThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"exists\": false",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenNewAndNotExistsThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"new\": true,",
                "\"exists\": false",
             "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenMultipleFilesThenMultipleEventsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "},",
            "{",
                "\"name\": \"foo/bar/boz\"",
            "}",
        "]}");
    EventBus eventBus = createStrictMock(EventBus.class);
    Capture<WatchEvent<Path>> firstEvent = newCapture();
    Capture<WatchEvent<Path>> secondEvent = newCapture();
    eventBus.post(capture(firstEvent));
    eventBus.post(capture(secondEvent));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertEquals("Path should match watchman output.",
        "foo/bar/baz",
        firstEvent.getValue().context().toString());
    assertEquals("Path should match watchman output.",
        "foo/bar/boz",
        secondEvent.getValue().context().toString());
  }

  @Test
  public void whenTooManyChangesThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    FakeProcess fakeProcess = new FakeProcess(0, watchmanOutput, "");
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        fakeProcess,
        new IncrementingFakeClock(),
        new ObjectMapper(),
        -1 /* overflow */,
        10000 /* timeout */);
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
    assertTrue("Watchman query process should be destroyed.", fakeProcess.isDestroyed());
    assertTrue("Watchman query process should be waited for.", fakeProcess.isWaitedFor());
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenWatchmanFailsThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(1, "", ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    try {
      watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
      fail("Should have thrown IOException.");
    } catch (WatchmanWatcherException e) {
      assertTrue("Should be watchman error", e.getMessage().startsWith("Watchman failed"));
    }
    verify(eventBus);
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenWatchmanInterruptedThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String message = "Boo!";
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    FakeProcess fakeProcess = new FakeProcess(
        0, "", "", Optional.of(new InterruptedException(message)));
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        fakeProcess,
        new IncrementingFakeClock(),
        new ObjectMapper());
    try {
      watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    } catch (InterruptedException e) {
      assertEquals("Should be test interruption.", e.getMessage(), message);
    }
    verify(eventBus);
    assertTrue("Watchman query process should be destroyed.", fakeProcess.isDestroyed());
    assertTrue("Watchman query process should be waited for.", fakeProcess.isWaitedFor());
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenQueryResultContainsErrorThenHumanReadableExceptionThrown()
      throws IOException, InterruptedException {
    String watchmanError = "Watch does not exist.";
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"error\": \"" + watchmanError + "\"",
        "}");
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(anyObject());
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    try {
      watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
      fail("Should have thrown RuntimeException");
    } catch (RuntimeException e) {
      assertThat("Should contain watchman error.",
          e.getMessage(),
          Matchers.containsString(watchmanError));
    }
  }

  @Test(expected = WatchmanWatcherException.class)
  public void whenQueryResultContainsErrorThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"error\": \"Watch does not exist.\"",
        "}");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    try {
      watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    } finally {
      assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
    }
  }

  @Test
  public void whenWatchmanInstanceIsFreshAllCachesAreCleared()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"clock\": \"c:1386170113:26390:5:50273\",",
        "\"is_fresh_instance\": true,",
        "\"files\": []",
        "}");

    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus bus = new EventBus("watchman test");
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    WatchmanWatcher watcher = createWatcher(
        bus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper());
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void whenParseTimesOutThenOverflowGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"clock\": \"c:1386170113:26390:5:50273\",",
        "\"is_fresh_instance\": true,",
        "\"files\": []",
        "}");

    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus bus = new EventBus("watchman test");
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    FakeProcess fakeProcess = new FakeProcess(0, watchmanOutput, "");
    WatchmanWatcher watcher = createWatcher(
        bus,
        fakeProcess,
        new IncrementingFakeClock(),
        new ObjectMapper(),
        200 /* overflow */,
        -1 /* timeout */);
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue("Watchman query process should be destroyed.", fakeProcess.isDestroyed());
    assertTrue("Watchman query process should be waited for.", fakeProcess.isWaitedFor());
    assertTrue(overflowSeen);
  }

  @Test
  public void watchmanQueryWithRepoRelativePrefix() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "path/to/repo",
        Optional.of("project"),
        "uuid",
        Lists.<Path>newArrayList(),
        Lists.<String>newArrayList(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));

    assertThat(
        query,
        Matchers.containsString("\"relative_root\":\"project\""));
  }

  @Test
  public void watchmanQueryWithRepoPathNeedingEscapingFormatsToCorrectJson() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "/path/to/\"repo\"",
        Optional.<String>absent(),
        "uuid",
        Lists.<Path>newArrayList(),
        Lists.<String>newArrayList(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        "[\"query\",\"/path/to/\\\"repo\\\"\",{\"since\":\"n:buckduuid\"," +
        "\"expression\":[\"not\",[\"anyof\"," +
        "[\"type\",\"d\"]]]," +
        "\"empty_on_fresh_instance\":true,\"fields\":[\"name\",\"exists\",\"new\"]}]",
        query);
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsExpressionToQuery() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        Lists.newArrayList(Paths.get("foo"), Paths.get("bar/baz")),
        Lists.<String>newArrayList(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        "[\"query\",\"/path/to/repo\",{\"since\":\"n:buckduuid\"," +
        "\"expression\":[\"not\",[\"anyof\"," +
        "[\"type\",\"d\"]," +
        "[\"dirname\",\"foo\"]," +
        "[\"dirname\",\"bar/baz\"]]]," +
        "\"empty_on_fresh_instance\":true,\"fields\":[\"name\",\"exists\",\"new\"]}]",
        query);
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsMatchExpressionToQueryIfDirnameNotAvailable() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        Lists.newArrayList(Paths.get("foo"), Paths.get("bar/baz")),
        Lists.<String>newArrayList(),
        ImmutableSet.<Watchman.Capability>of());
    assertEquals(
        "[\"query\",\"/path/to/repo\",{\"since\":\"n:buckduuid\"," +
        "\"expression\":[\"not\",[\"anyof\"," +
        "[\"type\",\"d\"]," +
        "[\"match\",\"foo/*\",\"wholename\"]," +
        "[\"match\",\"bar/baz/*\",\"wholename\"]]]," +
        "\"empty_on_fresh_instance\":true,\"fields\":[\"name\",\"exists\",\"new\"]}]",
        query);
  }

  @Test
  public void watchmanQueryRelativizesExcludePaths() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        Lists.newArrayList(Paths.get("/path/to/repo/foo"), Paths.get("/path/to/repo/bar/baz")),
        Lists.<String>newArrayList(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        "[\"query\",\"/path/to/repo\",{\"since\":\"n:buckduuid\"," +
        "\"expression\":[\"not\",[\"anyof\"," +
        "[\"type\",\"d\"]," +
        "[\"dirname\",\"foo\"]," +
        "[\"dirname\",\"bar/baz\"]]]," +
        "\"empty_on_fresh_instance\":true,\"fields\":[\"name\",\"exists\",\"new\"]}]",
        query);
  }

  @Test
  public void watchmanQueryWithExcludeGlobsAddsExpressionToQuery() {
    String query = WatchmanWatcher.createQuery(
        new ObjectMapper(),
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        Lists.<Path>newArrayList(),
        Lists.newArrayList("*.pbxproj"),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        "[\"query\",\"/path/to/repo\",{\"since\":\"n:buckduuid\"," +
        "\"expression\":[\"not\",[\"anyof\"," +
        "[\"type\",\"d\"]," +
        "[\"match\",\"*.pbxproj\"]]]," +
        "\"empty_on_fresh_instance\":true,\"fields\":[\"name\",\"exists\",\"new\"]}]",
        query);
  }

  @Test
  public void whenWatchmanProducesAWarningThenOverflowEventNotGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
        "{",
        "\"warning\": \"message\"",
        "}",
        "]}");
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper(),
        10000 /* overflow */,
        10000 /* timeout */);
    watcher.postEvents(new BuckEventBus(new FakeClock(0), new BuildId()));
    verify(eventBus);
  }
  @Test
  public void whenWatchmanProducesAWarningThenConsoleEventGenerated()
      throws IOException, InterruptedException {
    String message = "Find me!";
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
        "{",
        "\"warning\": \"" + message + "\"",
        "}",
        "]}");
    Capture<ConsoleEvent> eventCapture = newCapture();
    EventBus eventBus = new EventBus("watchman test");
    BuckEventBus buckEventBus = createStrictMock(BuckEventBus.class);
    buckEventBus.post(capture(eventCapture));
    replay(buckEventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeProcess(0, watchmanOutput, ""),
        new IncrementingFakeClock(),
        new ObjectMapper(),
        10000 /* overflow */,
        10000 /* timeout */);
    watcher.postEvents(buckEventBus);
    verify(buckEventBus);
    assertThat(eventCapture.getValue().getMessage(), Matchers.containsString(message));
  }

  private WatchmanWatcher createWatcher(
      EventBus eventBus,
      FakeProcess fakeProcess,
      Clock clock,
      ObjectMapper objectMapper) {
    return createWatcher(
        eventBus,
        fakeProcess,
        clock,
        objectMapper,
        200 /* overflow */,
        10000 /* timeout */);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus,
                                        FakeProcess queryProcess,
                                        Clock clock,
                                        ObjectMapper objectMapper,
                                        int overflow,
                                        long timeoutMillis) {
    return new WatchmanWatcher(
        eventBus,
        clock,
        objectMapper,
        new FakeProcessExecutor(
            ImmutableMap.of(
                ProcessExecutorParams.builder()
                    .addCommand("watchman", "--server-encoding=json", "--no-pretty", "-j")
                    .build(),
                queryProcess,
                ProcessExecutorParams.builder()
                    .addCommand("watchman", "version")
                    .build(),
                new FakeProcess(0, "{\"version\":\"3.5.0\"}\n", ""))),
        overflow,
        timeoutMillis,
        "" /* query */);
  }
}
