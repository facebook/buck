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

package com.facebook.buck.event.console;

import java.util.logging.Level;

/**
 * Simpler higher-level alternative to {@link com.facebook.buck.util.Console}.
 *
 * <p>In particular, this console doesn't break super-console.
 */
public abstract class EventConsole {

  /** Print a message to the console with given log level. */
  public abstract void println(Level level, String message);

  /** Print a warning to the console. */
  public void warn(String message) {
    println(Level.WARNING, message);
  }

  /** Print a warning to the console. */
  public void err(String message) {
    println(Level.SEVERE, message);
  }
}
