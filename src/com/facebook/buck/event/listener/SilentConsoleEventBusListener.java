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
package com.facebook.buck.event.listener;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.util.Locale;
import java.util.logging.Level;

/**
 * A special console event listener for the silent case. Almost nothing should be printed in that
 * case, and it's easier to get it correct when we handle it explicitly.
 */
public class SilentConsoleEventBusListener extends AbstractConsoleEventBusListener {
  public SilentConsoleEventBusListener(
      RenderingConsole superConsole,
      Clock clock,
      Locale locale,
      ExecutionEnvironment executionEnvironment) {
    super(superConsole, clock, locale, executionEnvironment, false, 0, false, ImmutableSet.of());
    Preconditions.checkArgument(
        superConsole.getVerbosity().isSilent(),
        "SilentConsole shouldn't be used with a non-silent verbosity.");
  }

  @Override
  public void printSevereWarningDirectly(String line) {
    console.logLines(formatConsoleEvent(ConsoleEvent.severe(line)));
  }

  /** Logs only SEVERE events. */
  @Subscribe
  public void logEvent(ConsoleEvent event) {
    if (!event.getLevel().equals(Level.SEVERE)) {
      return;
    }
    console.logLines(formatConsoleEvent(event));
  }
}
