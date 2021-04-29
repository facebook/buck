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

package com.facebook.buck.io.watchman;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.RecursiveFileMatcher;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WatchmanWatcherTest {

  private static final AbsPath FAKE_ROOT = AbsPath.of(Paths.get("/fake/root").toAbsolutePath());
  private static final WatchmanWatcherQuery FAKE_QUERY =
      ImmutableWatchmanWatcherQuery.ofImpl(
          "/fake/root", "fake-expr", ImmutableList.of(), Optional.empty());
  private static final WatchmanQuery.Query FAKE_UUID_QUERY = FAKE_QUERY.toQuery("n:buckduuid");
  private static final WatchmanQuery.Query FAKE_CLOCK_QUERY = FAKE_QUERY.toQuery("c:0:0");

  private static final AbsPath FAKE_SECONDARY_ROOT =
      AbsPath.of(Paths.get("/fake/secondary").toAbsolutePath());
  private static final WatchmanWatcherQuery FAKE_SECONDARY_QUERY =
      ImmutableWatchmanWatcherQuery.ofImpl(
          "/fake/SECONDARY", "fake-expr", ImmutableList.of(), Optional.empty());

  private EventBus eventBus;
  private EventBuffer eventBuffer;

  @Before
  public void setUp() {
    eventBuffer = new EventBuffer();
    eventBus = new EventBus();
    eventBus.register(eventBuffer);
  }

  @After
  public void cleanUp() {
    // Clear interrupted state so it doesn't affect any other test.
    Thread.interrupted();
  }

  @Test
  public void whenFilesListIsEmptyThenNoEventsAreGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            false,
            "files",
            ImmutableList.of());
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertTrue(eventBuffer.events.isEmpty());
    assertTrue(eventBuffer.bigEvents.isEmpty());
  }

  @Test
  public void whenNameThenModifyEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "files", ImmutableList.of(ImmutableMap.<String, Object>of("name", "foo/bar/baz")));
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent pathEvent = eventBuffer.getOnlyEvent(WatchmanPathEvent.class);
    assertEquals(Kind.MODIFY, pathEvent.getKind());
    assertEquals(
        "Path should match watchman output.",
        ForwardRelativePath.of("foo/bar/baz"),
        pathEvent.getPath());
    assertEquals(ImmutableList.of(pathEvent), eventBuffer.getOnlyBigEvent().getPathEvents());
  }

  @Test
  public void whenNewIsTrueThenCreateEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "files",
            ImmutableList.of(ImmutableMap.<String, Object>of("name", "foo/bar/baz", "new", true)));
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent pathEvent = eventBuffer.getOnlyEvent(WatchmanPathEvent.class);
    assertEquals("Should be create event.", Kind.CREATE, pathEvent.getKind());
    assertEquals(ImmutableList.of(pathEvent), eventBuffer.getOnlyBigEvent().getPathEvents());
  }

  @Test
  public void whenExistsIsFalseThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "files",
            ImmutableList.of(
                ImmutableMap.<String, Object>of("name", "foo/bar/baz", "exists", false)));
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent pathEvent = eventBuffer.getOnlyEvent(WatchmanPathEvent.class);
    assertEquals("Should be delete event.", Kind.DELETE, pathEvent.getKind());
    assertEquals(ImmutableList.of(pathEvent), eventBuffer.getOnlyBigEvent().getPathEvents());
  }

  @Test
  public void whenNewAndNotExistsThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "files",
            ImmutableList.of(
                ImmutableMap.<String, Object>of(
                    "name", "foo/bar/baz",
                    "new", true,
                    "exists", false)));
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent pathEvent = eventBuffer.getOnlyEvent(WatchmanPathEvent.class);
    assertEquals("Should be delete event.", Kind.DELETE, pathEvent.getKind());
    assertEquals(ImmutableList.of(pathEvent), eventBuffer.getOnlyBigEvent().getPathEvents());
  }

  @Test
  public void whenMultipleFilesThenMultipleEventsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "files",
            ImmutableList.of(
                ImmutableMap.<String, Object>of("name", "foo/bar/baz"),
                ImmutableMap.<String, Object>of("name", "foo/bar/boz")));
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    List<WatchmanPathEvent> pathEvents = eventBuffer.filterEventsByClass(WatchmanPathEvent.class);
    assertEquals(
        "Path should match watchman output.",
        ForwardRelativePath.of("foo/bar/baz"),
        pathEvents.get(0).getPath());
    assertEquals(
        "Path should match watchman output.",
        ForwardRelativePath.of("foo/bar/boz"),
        pathEvents.get(1).getPath());
    assertEquals(pathEvents, eventBuffer.getOnlyBigEvent().getPathEvents());
  }

  @Test
  public void whenTooManyChangesThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    ImmutableList.Builder<ImmutableMap<String, Object>> changedFiles =
        new ImmutableList.Builder<>();
    // The threshold is 10000; go a little above that.
    for (int i = 0; i < 10010; i++) {
      changedFiles.add(ImmutableMap.of("name", "foo/bar/baz" + i));
    }
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of("files", changedFiles.build());
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void whenWatchmanFailsThenOverflowEventGenerated() throws InterruptedException {
    WatchmanWatcher watcher =
        createWatcher(
            eventBus,
            new FakeWatchmanClient(
                0 /* queryElapsedTimeNanos */,
                ImmutableMap.of(FAKE_UUID_QUERY, ImmutableMap.of()),
                new IOException("oops")),
            10000 /* timeout */);
    try {
      watcher.postEvents(
          BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown IOException.");
    } catch (IOException e) {
      assertTrue("Should be expected error", e.getMessage().startsWith("oops"));
    }
    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void whenWatchmanInterruptedThenOverflowEventGenerated() throws IOException {
    String message = "Boo!";
    WatchmanWatcher watcher =
        createWatcher(
            eventBus,
            new FakeWatchmanClient(
                0 /* queryElapsedTimeNanos */,
                ImmutableMap.of(FAKE_UUID_QUERY, ImmutableMap.of()),
                new InterruptedException(message)),
            10000 /* timeout */);
    try {
      watcher.postEvents(
          BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown InterruptedException");
    } catch (InterruptedException e) {
      assertEquals("Should be test interruption.", e.getMessage(), message);
    }
    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void whenQueryResultContainsErrorThenHumanReadableExceptionThrown()
      throws IOException, InterruptedException {
    String watchmanError = "Watch does not exist.";
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("version", "2.9.2", "error", watchmanError);
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    try {
      watcher.postEvents(
          BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown RuntimeException");
    } catch (RuntimeException e) {
      assertThat(
          "Should contain watchman error.", e.getMessage(), Matchers.containsString(watchmanError));
    }
  }

  @Test(expected = WatchmanWatcherException.class)
  public void whenQueryResultContainsErrorThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version", "2.9.2",
            "error", "Watch does not exist.");
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    try {
      watcher.postEvents(
          BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
          WatchmanWatcher.FreshInstanceAction.NONE);
    } finally {
      assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
      assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
    }
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsPostThenOverflowEventIsPosted()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            true,
            "files",
            ImmutableList.of());

    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsNoneThenNoEventIsPosted()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            true,
            "files",
            ImmutableList.of());

    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);

    assertTrue("no events were posted", eventBuffer.events.isEmpty());
    assertTrue("no events were posted", eventBuffer.bigEvents.isEmpty());
  }

  @Test
  public void whenParseTimesOutThenOverflowGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            true,
            "files",
            ImmutableList.of());

    WatchmanWatcher watcher =
        createWatcher(
            eventBus,
            new FakeWatchmanClient(
                10000000000L /* queryElapsedTimeNanos */,
                ImmutableMap.of(FAKE_UUID_QUERY, watchmanOutput)),
            -1 /* timeout */);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);

    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void watchmanQueryWithRepoRelativePrefix() {
    WatchmanWatcherQuery query =
        WatchmanWatcher.createQuery(
            ProjectWatch.of("path/to/repo", Optional.of("project")),
            ImmutableSet.of(),
            ImmutableSet.of(Capability.DIRNAME));

    assertEquals(query.toQuery("").getRelativeRoot().get(), "project");
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsExpressionToQuery() {
    WatchmanWatcherQuery query =
        WatchmanWatcher.createQuery(
            ProjectWatch.of("/path/to/repo", Optional.empty()),
            ImmutableSet.of(
                RecursiveFileMatcher.of(RelPath.get("foo")),
                RecursiveFileMatcher.of(RelPath.get("bar/baz"))),
            ImmutableSet.of(Capability.DIRNAME));
    assertEquals(
        ImmutableWatchmanWatcherQuery.ofImpl(
            "/path/to/repo",
            ImmutableList.of(
                "not",
                ImmutableList.of(
                    "anyof",
                    ImmutableList.of("type", "d"),
                    ImmutableList.of("dirname", "foo"),
                    ImmutableList.of("dirname", MorePaths.pathWithPlatformSeparators("bar/baz")))),
            ImmutableList.of("name", "exists", "new", "type"),
            Optional.empty()),
        query);
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsMatchExpressionToQueryIfDirnameNotAvailable() {
    WatchmanWatcherQuery query =
        WatchmanWatcher.createQuery(
            ProjectWatch.of("/path/to/repo", Optional.empty()),
            ImmutableSet.of(
                RecursiveFileMatcher.of(RelPath.get("foo")),
                RecursiveFileMatcher.of(RelPath.get("bar/baz"))),
            ImmutableSet.of());
    assertEquals(
        ImmutableWatchmanWatcherQuery.ofImpl(
            "/path/to/repo",
            ImmutableList.of(
                "not",
                ImmutableList.of(
                    "anyof",
                    ImmutableList.of("type", "d"),
                    ImmutableList.of("match", "foo" + File.separator + "**", "wholename"),
                    ImmutableList.of(
                        "match",
                        "bar" + File.separator + "baz" + File.separator + "**",
                        "wholename"))),
            ImmutableList.of("name", "exists", "new", "type"),
            Optional.empty()),
        query);
  }

  @Test
  public void watchmanQueryRelativizesExcludePaths() {
    String watchRoot = Paths.get("/path/to/repo").toAbsolutePath().toString();
    WatchmanWatcherQuery query =
        WatchmanWatcher.createQuery(
            ProjectWatch.of(watchRoot, Optional.empty()),
            ImmutableSet.of(
                RecursiveFileMatcher.of(RelPath.get("foo")),
                RecursiveFileMatcher.of(RelPath.get("bar/baz"))),
            ImmutableSet.of(Capability.DIRNAME));
    assertEquals(
        ImmutableWatchmanWatcherQuery.ofImpl(
            watchRoot,
            ImmutableList.of(
                "not",
                ImmutableList.of(
                    "anyof",
                    ImmutableList.of("type", "d"),
                    ImmutableList.of("dirname", "foo"),
                    ImmutableList.of("dirname", MorePaths.pathWithPlatformSeparators("bar/baz")))),
            ImmutableList.of("name", "exists", "new", "type"),
            Optional.empty()),
        query);
  }

  @Test
  public void watchmanQueryWithExcludeGlobsAddsExpressionToQuery() {
    WatchmanWatcherQuery query =
        WatchmanWatcher.createQuery(
            ProjectWatch.of("/path/to/repo", Optional.empty()),
            ImmutableSet.of(GlobPatternMatcher.of("*.pbxproj")),
            ImmutableSet.of(Capability.DIRNAME));
    assertEquals(
        ImmutableWatchmanWatcherQuery.ofImpl(
            "/path/to/repo",
            ImmutableList.of(
                "not",
                ImmutableList.of(
                    "anyof",
                    ImmutableList.of("type", "d"),
                    ImmutableList.of(
                        "match",
                        "*.pbxproj",
                        "wholename",
                        ImmutableMap.<String, Object>of("includedotfiles", true)))),
            ImmutableList.of("name", "exists", "new", "type"),
            Optional.empty()),
        query);
  }

  @Test
  public void whenWatchmanProducesAWarningThenOverflowEventNotGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("files", ImmutableList.of(), "warning", "message");
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertTrue(eventBuffer.events.isEmpty());
    assertTrue(eventBuffer.bigEvents.isEmpty());
  }

  @Test
  public void whenWatchmanProducesAWarningThenDiagnosticEventGenerated()
      throws IOException, InterruptedException {
    String message = "Find me!";
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("files", ImmutableList.of(), "warning", message);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    buckEventBus.register(listener);
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    watcher.postEvents(buckEventBus, WatchmanWatcher.FreshInstanceAction.NONE);
    ImmutableList<WatchmanDiagnosticEvent> diagnostics =
        RichStream.from(listener.getEvents())
            .filter(WatchmanDiagnosticEvent.class)
            .toImmutableList();
    assertThat(diagnostics, hasSize(1));
    assertThat(diagnostics.get(0).getDiagnostic().getMessage(), Matchers.containsString(message));
  }

  @Test
  public void whenWatchmanProducesAWarningThenWarningAddedToCache()
      throws IOException, InterruptedException {
    String message = "I'm a warning!";
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("files", ImmutableList.of(), "warning", message);
    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    Set<WatchmanDiagnostic> diagnostics = new HashSet<>();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    buckEventBus.register(new WatchmanDiagnosticEventListener(buckEventBus, diagnostics));
    watcher.postEvents(buckEventBus, WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(
        diagnostics, hasItem(WatchmanDiagnostic.of(WatchmanDiagnostic.Level.WARNING, message)));
  }

  @Test
  public void watcherInsertsAndUpdatesClockId() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("clock", "c:0:1", "files", ImmutableList.of());
    WatchmanWatcher watcher =
        createWatcher(
            eventBus,
            new FakeWatchmanClient(
                0 /* queryElapsedTimeNanos */, ImmutableMap.of(FAKE_CLOCK_QUERY, watchmanOutput)),
            10000 /* timeout */,
            "c:0:0" /* sinceParam */);
    assertEquals(watcher.getWatchmanQuery(FAKE_ROOT).get().getSince(), Optional.of("c:0:0"));

    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    assertEquals(watcher.getWatchmanQuery(FAKE_ROOT).get().getSince(), Optional.of("c:0:1"));
  }

  @Test
  public void watcherOverflowUpdatesClockId() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of("clock", "c:1:0", "is_fresh_instance", true);
    WatchmanWatcher watcher =
        createWatcher(
            eventBus,
            new FakeWatchmanClient(
                0 /* queryElapsedTimeNanos */, ImmutableMap.of(FAKE_CLOCK_QUERY, watchmanOutput)),
            10000 /* timeout */,
            "c:0:0" /* sinceParam */);
    assertEquals(watcher.getWatchmanQuery(FAKE_ROOT).get().getSince(), Optional.of("c:0:0"));

    watcher.postEvents(
        BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    assertEquals(watcher.getWatchmanQuery(FAKE_ROOT).get().getSince(), Optional.of("c:1:0"));
    assertEquals(1, eventBuffer.filterEventsByClass(WatchmanOverflowEvent.class).size());
    assertEquals(1, eventBuffer.getOnlyBigEvent().getOverflowEvents().size());
  }

  @Test
  public void whenWatchmanReportsZeroFilesChangedThenPostEvent()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of("files", ImmutableList.of());

    WatchmanWatcher watcher = createWatcher(eventBus, watchmanOutput);
    Set<BuckEvent> events = new HashSet<>();
    BuckEventBus bus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchmanStatusEvent event) {
            events.add(event);
          }
        });
    watcher.postEvents(bus, WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean zeroFilesChangedSeen = false;
    System.err.printf("Events: %d%n", events.size());
    for (BuckEvent event : events) {
      zeroFilesChangedSeen |= event.getEventName().equals("WatchmanZeroFileChanges");
    }
    assertTrue(zeroFilesChangedSeen);
  }

  @Test
  public void whenWatchmanCellReportsFilesChangedThenPostEvent()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanRootOutput = ImmutableMap.of("files", ImmutableList.of());
    ImmutableMap<String, Object> watchmanSecondaryOutput =
        ImmutableMap.of(
            "files", ImmutableList.of(ImmutableMap.<String, Object>of("name", "foo/bar/baz")));

    WatchmanClient client =
        new FakeWatchmanClient(
            0,
            ImmutableMap.of(
                FAKE_CLOCK_QUERY,
                watchmanRootOutput,
                FAKE_SECONDARY_QUERY.toQuery("c:0:0"),
                watchmanSecondaryOutput));
    WatchmanWatcher watcher =
        new WatchmanWatcher(
            eventBus,
            client,
            10000,
            ImmutableMap.of(
                FAKE_ROOT, FAKE_QUERY,
                FAKE_SECONDARY_ROOT, FAKE_SECONDARY_QUERY),
            ImmutableMap.of(
                FAKE_ROOT, new WatchmanCursor("c:0:0"),
                FAKE_SECONDARY_ROOT, new WatchmanCursor("c:0:0")),
            /* numThreads */ 1);
    Set<BuckEvent> events = new HashSet<>();
    BuckEventBus bus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchmanStatusEvent event) {
            events.add(event);
          }
        });
    watcher.postEvents(bus, WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean zeroFilesChangedSeen = false;
    System.err.printf("Events: %d%n", events.size());
    for (BuckEvent event : events) {
      System.err.printf("Event: %s%n", event);
      zeroFilesChangedSeen |= event.getEventName().equals("WatchmanZeroFileChanges");
    }
    assertFalse(zeroFilesChangedSeen);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus, ImmutableMap<String, Object> response) {
    return createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */, ImmutableMap.of(FAKE_UUID_QUERY, response)),
        10000 /* timeout */);
  }

  private WatchmanWatcher createWatcher(
      EventBus eventBus, FakeWatchmanClient watchmanClient, long timeoutMillis) {
    return createWatcher(eventBus, watchmanClient, timeoutMillis, "n:buckduuid" /* sinceCursor */);
  }

  private WatchmanWatcher createWatcher(
      EventBus eventBus,
      FakeWatchmanClient watchmanClient,
      long timeoutMillis,
      String sinceCursor) {
    return new WatchmanWatcher(
        eventBus,
        watchmanClient,
        timeoutMillis,
        ImmutableMap.of(FAKE_ROOT, FAKE_QUERY),
        ImmutableMap.of(FAKE_ROOT, new WatchmanCursor(sinceCursor)),
        /* numThreads */ 1);
  }

  private static class EventBuffer {
    public final List<WatchmanEvent> events = new ArrayList<>();
    public final List<WatchmanWatcherOneBigEvent> bigEvents = new ArrayList<>();

    @Subscribe
    public void on(WatchmanEvent event) {
      events.add(event);
    }

    @Subscribe
    public void on(WatchmanWatcherOneBigEvent bigEvent) {
      bigEvents.add(bigEvent);
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> List<E> filterEventsByClass(Class<E> clazz) {
      return events.stream()
          .filter(e -> clazz.isAssignableFrom(e.getClass()))
          .map(e -> (E) e)
          .collect(Collectors.toList());
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> E getOnlyEvent(Class<E> clazz) {
      List<E> filteredEvents = filterEventsByClass(clazz);
      assertEquals(
          String.format("Expected only one event of type %s", clazz.getName()),
          1,
          filteredEvents.size());
      return filteredEvents.get(0);
    }

    public WatchmanWatcherOneBigEvent getOnlyBigEvent() {
      assertEquals(1, bigEvents.size());
      return bigEvents.iterator().next();
    }
  }
}
