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
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.FakeWatchmanClient;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanDiagnostic;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.timing.FakeClock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import org.easymock.Capture;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
public class WatchmanWatcherTest {

  private static final List<Object> FAKE_QUERY = ImmutableList.<Object>of("fake-query");

  @After
  public void cleanUp() {
    // Clear interrupted state so it doesn't affect any other test.
    Thread.interrupted();
  }

  @Test
  public void whenFilesListIsEmptyThenNoEventsAreGenerated()
    throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", false,
        "files", ImmutableList.of());
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
  }

  @Test
  public void whenNameThenModifyEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of("name", "foo/bar/baz")));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be modify event.",
        StandardWatchEventKinds.ENTRY_MODIFY,
        eventCapture.getValue().kind());
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/baz"),
        eventCapture.getValue().context().toString());
  }

  @Test
  public void whenNewIsTrueThenCreateEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "new", true)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be create event.",
        StandardWatchEventKinds.ENTRY_CREATE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenExistsIsFalseThenDeleteEventIsGenerated()
    throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "exists", false)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenNewAndNotExistsThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "new", true,
                "exists", false)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenMultipleFilesThenMultipleEventsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of("name", "foo/bar/baz"),
            ImmutableMap.<String, Object>of("name", "foo/bar/boz")));
    EventBus eventBus = createStrictMock(EventBus.class);
    Capture<WatchEvent<Path>> firstEvent = newCapture();
    Capture<WatchEvent<Path>> secondEvent = newCapture();
    eventBus.post(capture(firstEvent));
    eventBus.post(capture(secondEvent));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/baz"),
        firstEvent.getValue().context().toString());
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/boz"),
        secondEvent.getValue().context().toString());
  }

  @Test
  public void whenTooManyChangesThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz")));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_QUERY, watchmanOutput)),
        -1 /* overflow */,
        10000 /* timeout */);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
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
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_QUERY, ImmutableMap.<String, Object>of()),
            new IOException("oops")),
        200 /* overflow */,
        10000 /* timeout */);
    try {
      watcher.postEvents(
          new BuckEventBus(new FakeClock(0), new BuildId()),
          new WatchmanDiagnosticCache(),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown IOException.");
    } catch (IOException e) {
      assertTrue("Should be expected error", e.getMessage().startsWith("oops"));
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
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_QUERY, ImmutableMap.<String, Object>of()),
            new InterruptedException(message)),
        200 /* overflow */,
        10000 /* timeout */);
    try {
      watcher.postEvents(
          new BuckEventBus(new FakeClock(0), new BuildId()),
          new WatchmanDiagnosticCache(),
          WatchmanWatcher.FreshInstanceAction.NONE);
    } catch (InterruptedException e) {
      assertEquals("Should be test interruption.", e.getMessage(), message);
    }
    verify(eventBus);
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenQueryResultContainsErrorThenHumanReadableExceptionThrown()
      throws IOException, InterruptedException {
    String watchmanError = "Watch does not exist.";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "error", watchmanError);
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(anyObject());
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    try {
      watcher.postEvents(
          new BuckEventBus(new FakeClock(0), new BuildId()),
          new WatchmanDiagnosticCache(),
          WatchmanWatcher.FreshInstanceAction.NONE);
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
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "error", "Watch does not exist.");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    try {
      watcher.postEvents(
          new BuckEventBus(new FakeClock(0), new BuildId()),
          new WatchmanDiagnosticCache(),
          WatchmanWatcher.FreshInstanceAction.NONE);
    } finally {
      assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
    }
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsPostThenAllCachesAreCleared()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

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
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsNoneThenCachesNotCleared()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

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
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertFalse(overflowSeen);
  }

  @Test
  public void whenParseTimesOutThenOverflowGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

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
        new FakeWatchmanClient(
            10000000000L /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_QUERY, watchmanOutput)),
        200 /* overflow */,
        -1 /* timeout */);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void watchmanQueryWithRepoRelativePrefix() {
    List<Object> query = WatchmanWatcher.createQuery(
        "path/to/repo",
        Optional.of("project"),
        "uuid",
        ImmutableSet.<PathOrGlobMatcher>of(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));

    assertThat(
        query,
        hasItem(hasEntry("relative_root", "project")));
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsExpressionToQuery() {
    List<Object> query = WatchmanWatcher.createQuery(
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        ImmutableSet.<PathOrGlobMatcher>of(
            new PathOrGlobMatcher(Paths.get("foo")),
            new PathOrGlobMatcher(Paths.get("bar/baz"))),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        ImmutableList.of(
            "query",
            "/path/to/repo",
            ImmutableMap.of(
                "since", "n:buckduuid",
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of("dirname", "foo"),
                        ImmutableList.of(
                            "dirname",
                            MorePaths.pathWithPlatformSeparators("bar/baz")))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsMatchExpressionToQueryIfDirnameNotAvailable() {
    List<Object> query = WatchmanWatcher.createQuery(
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        ImmutableSet.<PathOrGlobMatcher>of(
            new PathOrGlobMatcher(Paths.get("foo")),
            new PathOrGlobMatcher(Paths.get("bar/baz"))),
        ImmutableSet.<Watchman.Capability>of());
    assertEquals(
        ImmutableList.of(
            "query",
            "/path/to/repo",
            ImmutableMap.of(
                "since", "n:buckduuid",
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of(
                            "match",
                            "foo" + File.separator + "*",
                            "wholename"),
                        ImmutableList.of(
                            "match",
                            "bar" + File.separator + "baz" + File.separator + "*",
                            "wholename"))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryRelativizesExcludePaths() {
    String watchRoot = Paths.get("/path/to/repo").toAbsolutePath().toString();
    List<Object> query = WatchmanWatcher.createQuery(
        watchRoot,
        Optional.<String>absent(),
        "uuid",
        ImmutableSet.<PathOrGlobMatcher>of(
            new PathOrGlobMatcher(Paths.get("/path/to/repo/foo").toAbsolutePath()),
            new PathOrGlobMatcher(Paths.get("/path/to/repo/bar/baz").toAbsolutePath())),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        ImmutableList.of(
            "query",
            watchRoot,
            ImmutableMap.of(
                "since", "n:buckduuid",
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of("dirname", "foo"),
                        ImmutableList.of(
                            "dirname",
                            MorePaths.pathWithPlatformSeparators("bar/baz")))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryWithExcludeGlobsAddsExpressionToQuery() {
    List<Object> query = WatchmanWatcher.createQuery(
        "/path/to/repo",
        Optional.<String>absent(),
        "uuid",
        ImmutableSet.<PathOrGlobMatcher>of(
            new PathOrGlobMatcher("*.pbxproj")),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        ImmutableList.of(
            "query",
            "/path/to/repo",
            ImmutableMap.of(
                "since", "n:buckduuid",
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of(
                            "match",
                            "*.pbxproj",
                            "wholename",
                            ImmutableMap.<String, Object>of("includedotfiles", true)))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void whenWatchmanProducesAWarningThenOverflowEventNotGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(),
        "warning", "message");
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
  }
  @Test
  public void whenWatchmanProducesAWarningThenConsoleEventGenerated()
      throws IOException, InterruptedException {
    String message = "Find me!";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(),
        "warning", message);
    Capture<ConsoleEvent> eventCapture = newCapture();
    EventBus eventBus = new EventBus("watchman test");
    BuckEventBus buckEventBus = createStrictMock(BuckEventBus.class);
    buckEventBus.post(capture(eventCapture));
    replay(buckEventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        buckEventBus,
        new WatchmanDiagnosticCache(),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(buckEventBus);
    assertThat(eventCapture.getValue().getMessage(), Matchers.containsString(message));
  }

  @Test
  public void whenWatchmanProducesAWarningThenWarningAddedToCache()
      throws IOException, InterruptedException {
    String message = "I'm a warning!";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "files", ImmutableList.of(),
        "warning", message);
    EventBus eventBus = new EventBus("watchman test");
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    Set<WatchmanDiagnostic> diagnostics = new HashSet<>();
    WatchmanDiagnosticCache watchmanDiagnosticCache = new WatchmanDiagnosticCache(diagnostics);
    watcher.postEvents(
        new BuckEventBus(new FakeClock(0), new BuildId()),
        watchmanDiagnosticCache,
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(
        diagnostics,
        hasItem(
            WatchmanDiagnostic.of(
                WatchmanDiagnostic.Level.WARNING,
                message)));
  }

  private WatchmanWatcher createWatcher(
      EventBus eventBus,
      ImmutableMap<String, ? extends Object> response) {
    return createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_QUERY, response)),
        200 /* overflow */,
        10000 /* timeout */);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus,
                                        FakeWatchmanClient watchmanClient,
                                        int overflow,
                                        long timeoutMillis) {
    return new WatchmanWatcher(
        eventBus,
        watchmanClient,
        overflow,
        timeoutMillis,
        FAKE_QUERY);
  }
}
