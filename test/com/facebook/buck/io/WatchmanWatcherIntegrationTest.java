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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanWatcherIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Watchman watchman;
  private EventBus eventBus;
  private WatchmanEventCollector watchmanEventCollector;

  @Before
  public void setUp() throws InterruptedException, IOException {
    // Create an empty watchman config file.
    Files.write(tmp.getRoot().resolve(".watchmanconfig"), new byte[0]);

    WatchmanFactory watchmanFactory = new WatchmanFactory();
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            ImmutableMap.copyOf(System.getenv()),
            new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty()),
            new DefaultClock(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());

    eventBus = new EventBus();
    watchmanEventCollector = new WatchmanEventCollector();
    eventBus.register(watchmanEventCollector);
  }

  @Test
  public void ignoreDotFileInGlob() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(new PathOrGlobMatcher("**/*.swp"));

    // Create a dot-file which should be ignored by the above glob.
    Path path = tmp.getRoot().getFileSystem().getPath("foo/bar/.hello.swp");
    Files.createDirectories(tmp.getRoot().resolve(path).getParent());
    Files.write(tmp.getRoot().resolve(path), new byte[0]);

    // Verify we don't get an event for the path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(watchmanEventCollector.getEvents(), Matchers.empty());
  }

  @Test
  public void globMatchesWholeName() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(new PathOrGlobMatcher("*.txt"));

    // Create a dot-file which should be ignored by the above glob.
    Path path = tmp.getRoot().getFileSystem().getPath("foo/bar/hello.txt");
    Files.createDirectories(tmp.getRoot().resolve(path).getParent());
    Files.write(tmp.getRoot().resolve(path), new byte[0]);

    // Verify we still get an event for the created path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    ImmutableList<WatchmanEvent> events = watchmanEventCollector.getEvents();
    assertThat(events.size(), Matchers.equalTo(1));
    WatchmanPathEvent event = (WatchmanPathEvent) events.get(0);
    Path eventPath = event.getPath();
    assertThat(eventPath, Matchers.equalTo(path));
    assertSame(event.getKind(), WatchmanPathEvent.Kind.CREATE);
  }

  // Create a watcher for the given ignore paths, clearing the initial overflow event before
  // returning it.
  private WatchmanWatcher createWatchmanWatcher(PathOrGlobMatcher... ignorePaths)
      throws IOException, InterruptedException {

    WatchmanWatcher watcher =
        new WatchmanWatcher(
            watchman,
            eventBus,
            ImmutableSet.copyOf(ignorePaths),
            ImmutableMap.of(
                tmp.getRoot(),
                new WatchmanCursor(
                    new StringBuilder("n:buckd").append(UUID.randomUUID()).toString())),
            /* numThreads */ 1);

    // Clear out the initial overflow event.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    watchmanEventCollector.clear();

    return watcher;
  }

  private static final class WatchmanEventCollector {

    private final List<WatchmanEvent> events = new ArrayList<>();

    @Subscribe
    protected void handle(WatchmanEvent event) {
      events.add(event);
    }

    public void clear() {
      events.clear();
    }

    public ImmutableList<WatchmanEvent> getEvents() {
      return ImmutableList.copyOf(events);
    }
  }
}
