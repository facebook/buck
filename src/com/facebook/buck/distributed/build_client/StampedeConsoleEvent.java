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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;

/**
 * Wrapper over ConsoleEvent for messages that should only be displayed when the stampede console is
 * enabled.
 */
public class StampedeConsoleEvent extends AbstractBuckEvent {
  private final ConsoleEvent consoleEvent;

  public StampedeConsoleEvent(ConsoleEvent consoleEvent) {
    super(EventKey.unique());
    this.consoleEvent = consoleEvent;
  }

  public ConsoleEvent getConsoleEvent() {
    return consoleEvent;
  }

  @Override
  protected String getValueString() {
    return String.format("%s: %s", consoleEvent.getLevel(), consoleEvent.getMessage());
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }
}
