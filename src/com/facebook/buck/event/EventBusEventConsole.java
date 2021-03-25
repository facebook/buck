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

package com.facebook.buck.event;

import com.facebook.buck.event.console.EventConsole;
import java.util.logging.Level;

/** Event console implementation which sends events through event bus to super-console. */
public class EventBusEventConsole extends EventConsole {

  private final BuckEventBus eventBus;

  public EventBusEventConsole(BuckEventBus eventBus) {
    this.eventBus = eventBus;
  }

  @Override
  public void println(Level level, String message) {
    eventBus.post(ConsoleEvent.create(level, message));
  }
}
