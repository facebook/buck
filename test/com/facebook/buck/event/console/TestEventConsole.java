/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.event.console;

import java.util.logging.Level;

/** Event console which prints to stderr. To be used in tests. */
public class TestEventConsole extends EventConsole {

  private String lastPrintedMessage;

  @Override
  public void println(Level level, String message) {
    this.lastPrintedMessage = level + " " + message;
    System.err.println(this.lastPrintedMessage);
  }

  public String getLastPrintedMessage() {
    return this.lastPrintedMessage;
  }

  public void clearMessages() {
    this.lastPrintedMessage = null;
  }
}
