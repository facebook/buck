/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.parser.decorators;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Decorator for {@link ProjectBuildFileParser} that is responsible for reporting parse lifecycle
 * events like start and finish.
 *
 * <p>This decouples status reporting functionality so that it can be used with different underlying
 * {@link ProjectBuildFileParser}s.
 */
public class EventReportingProjectBuildFileParser implements ProjectBuildFileParser {

  private final ProjectBuildFileParser delegate;
  private final BuckEventBus eventBus;
  private final Object eventLock;

  @GuardedBy("eventLock")
  @Nullable
  private ProjectBuildFileParseEvents.Started projectBuildFileParseEventStarted;

  private EventReportingProjectBuildFileParser(
      ProjectBuildFileParser delegate, BuckEventBus eventBus) {
    this.delegate = delegate;
    this.eventBus = eventBus;
    this.eventLock = new Object();
  }

  /** Possibly post a start event making sure it's done only once. */
  private void maybePostStartEvent() {
    synchronized (eventLock) {
      if (projectBuildFileParseEventStarted == null) {
        projectBuildFileParseEventStarted = new ProjectBuildFileParseEvents.Started();
        eventBus.post(projectBuildFileParseEventStarted);
      }
    }
  }

  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    maybePostStartEvent();
    return delegate.getBuildFileManifest(buildFile, processedBytes);
  }

  @Override
  public void reportProfile() throws IOException {
    delegate.reportProfile();
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    maybePostFinishedEvent();
    delegate.close();
  }

  /** Post a finished event in case start event was posted previously. */
  private void maybePostFinishedEvent() {
    synchronized (eventLock) {
      if (projectBuildFileParseEventStarted != null) {
        eventBus.post(
            new ProjectBuildFileParseEvents.Finished(
                Preconditions.checkNotNull(projectBuildFileParseEventStarted)));
      }
    }
  }

  /**
   * Static factory method for producing instances of {@link
   * com.facebook.buck.parser.decorators.EventReportingProjectBuildFileParser}.
   */
  public static EventReportingProjectBuildFileParser of(
      ProjectBuildFileParser delegate, BuckEventBus eventBus) {
    return new EventReportingProjectBuildFileParser(delegate, eventBus);
  }
}
