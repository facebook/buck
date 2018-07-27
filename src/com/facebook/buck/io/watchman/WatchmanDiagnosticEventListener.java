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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

/** Deduplicating event bus listener that outputs deduplicated messages over the given event bus. */
@ThreadSafe
public class WatchmanDiagnosticEventListener implements BuckEventListener {
  private static final Logger LOG = Logger.get(WatchmanDiagnosticEventListener.class);

  private final Set<WatchmanDiagnostic> diagnosticCache;
  private final BuckEventBus buckEventBus;

  public WatchmanDiagnosticEventListener(BuckEventBus outputEventBus) {
    this(outputEventBus, Sets.newConcurrentHashSet());
  }

  @VisibleForTesting
  public WatchmanDiagnosticEventListener(
      BuckEventBus outputEventBus, Set<WatchmanDiagnostic> diagnosticCache) {
    this.diagnosticCache = diagnosticCache;
    this.buckEventBus = outputEventBus;
  }

  @Subscribe
  public void onEvent(WatchmanDiagnosticEvent event) {
    WatchmanDiagnostic diagnostic = event.getDiagnostic();
    if (diagnosticCache.add(diagnostic)) {
      LOG.verbose("Added new diagnostic: %s", diagnostic);
      switch (diagnostic.getLevel()) {
        case WARNING:
          buckEventBus.post(
              ConsoleEvent.warning("Watchman raised a warning: %s", diagnostic.getMessage()));
          break;
        case ERROR:
          buckEventBus.post(
              ConsoleEvent.severe("Watchman raised an error: %s", diagnostic.getMessage()));
          break;
      }
    } else {
      LOG.verbose("Added duplicate diagnostic: %s", diagnostic);
    }
  }
}
