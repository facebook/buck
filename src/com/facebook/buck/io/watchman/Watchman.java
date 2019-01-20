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

package com.facebook.buck.io.watchman;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Contains the configuration for a Watchman client as well as the ability to create a client. */
public abstract class Watchman {

  private final ImmutableMap<Path, ProjectWatch> projectWatches;
  private final ImmutableSet<Capability> capabilities;
  private final ImmutableMap<String, String> clockIds;
  private final Optional<Path> transportPath;

  public Watchman(
      ImmutableMap<Path, ProjectWatch> projectWatches,
      ImmutableSet<Capability> capabilities,
      ImmutableMap<String, String> clockIds,
      Optional<Path> transportPath) {
    this.projectWatches = projectWatches;
    this.capabilities = capabilities;
    this.clockIds = clockIds;
    this.transportPath = transportPath;
  }

  public ImmutableMap<Path, WatchmanCursor> buildClockWatchmanCursorMap() {
    ImmutableMap.Builder<Path, WatchmanCursor> cursorBuilder = ImmutableMap.builder();
    for (Map.Entry<Path, ProjectWatch> entry : projectWatches.entrySet()) {
      String clockId = clockIds.get(entry.getValue().getWatchRoot());
      Preconditions.checkNotNull(
          clockId, "No ClockId found for watch root %s", entry.getValue().getWatchRoot());
      cursorBuilder.put(entry.getKey(), new WatchmanCursor(clockId));
    }
    return cursorBuilder.build();
  }

  public ImmutableMap<Path, WatchmanCursor> buildNamedWatchmanCursorMap() {
    ImmutableMap.Builder<Path, WatchmanCursor> cursorBuilder = ImmutableMap.builder();
    for (Path cellPath : projectWatches.keySet()) {
      cursorBuilder.put(cellPath, new WatchmanCursor("n:buckd" + UUID.randomUUID()));
    }
    return cursorBuilder.build();
  }

  public ImmutableMap<Path, ProjectWatch> getProjectWatches() {
    return projectWatches;
  }

  public ImmutableSet<Capability> getCapabilities() {
    return capabilities;
  }

  public ImmutableMap<String, String> getClockIds() {
    return clockIds;
  }

  public boolean hasWildmatchGlob() {
    return capabilities.contains(Capability.WILDMATCH_GLOB);
  }

  public Optional<Path> getTransportPath() {
    return transportPath;
  }

  /**
   * Note this method will throw an {@link IOException} if:
   *
   * <ul>
   *   <li>{@link #getTransportPath()} returns {@link Optional#empty()}
   *   <li>It cannot establish a connection to Watchman.
   * </ul>
   *
   * @return a new client that the caller is responsible for closing.
   */
  public abstract WatchmanClient createClient() throws IOException;
}
