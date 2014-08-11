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

import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.FakeClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

public class WatchmanIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  /**
   * A Watchman watch implemented as a Closeable resource so that try-with-resources
   * blocks can be used to ensure that the watch is deleted correctly after use.
   */
  private class WatchmanWatch implements Closeable {
    private final Path path;

    public WatchmanWatch(Path path) throws IOException, InterruptedException {
      this.path = Preconditions.checkNotNull(path);
      Process process = new ProcessBuilder(
          "watchman",
          "watch",
          path.toAbsolutePath().toString()).start();
      process.waitFor();
    }

    @Override
    public void close() throws IOException {
      Process process = new ProcessBuilder(
          "watchman",
          "watch-del",
          path.toAbsolutePath().toString()).start();
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        e.printStackTrace();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Verifies that Watchman does not generate new file events for existing files when
   * WatchmanWatcher.postEvents() is called for the first time.
   */
  @Test
  @Ignore("Disabled until we resolve the test failure on CI servers.")
  public void whenPostEventsFirstCalledThenNoEventsArePosted()
      throws IOException, InterruptedException {
    assumeTrue(WatchmanWatcher.isWatchmanAvailable()); // Watchman must be installed.
    tmp.newFile("CreatedBeforeWatch");
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    try (WatchmanWatch watch = new WatchmanWatch(tmp.getRoot().toPath())) {
      WatchmanWatcher watcher = new WatchmanWatcher(
          new ProjectFilesystem(tmp.getRoot().toPath()),
          eventBus,
          new FakeClock(0),
          new ObjectMapper(),
          new ArrayList<Path>(),
          new ArrayList<String>());
      watcher.postEvents();
    }
    verify(eventBus);
  }
}
