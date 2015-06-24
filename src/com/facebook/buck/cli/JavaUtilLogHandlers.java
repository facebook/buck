/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.log.ConsoleHandler;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.util.concurrent.Callable;
import java.util.logging.Handler;
import java.util.logging.Logger;

public class JavaUtilLogHandlers {
  // Utility class. Do not instantiate.
  private JavaUtilLogHandlers() { }

  public static Optional<ConsoleHandler> getConsoleHandler() {
    // We can't stop someone from mutating this array, but we can minimize the chance.
    ImmutableList<Handler> handlers = ImmutableList.copyOf(Logger.getLogger("").getHandlers());
    for (Handler handler : handlers) {
      if (handler instanceof ConsoleHandler) {
        return Optional.of((ConsoleHandler) handler);
      } else if (handler instanceof Callable<?>) {
        // com.facebook.buck.cli.bootstrapper.ConsoleHandler is not
        // visible to us, so thunk it through Callable<Handler> so
        // we can get at the real ConsoleHandler.
        Callable<?> callable = (Callable<?>) handler;
        try {
          Object innerHandler = callable.call();
          if (innerHandler instanceof ConsoleHandler) {
            return Optional.of((ConsoleHandler) innerHandler);
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    // If we hit this, something has gone wrong and we can't find the
    // ConsoleHandler from the list of registered log handlers.
    //
    // This means SuperConsole will not be notified when we print log
    // messages to console, so it will not disable itself, causing log
    // console logs to collide with SuperConsole output.
    System.err.println(
        "WARNING: Cannot find ConsoleHandler log handler. " +
        "Logs printed to console will likely be lost.");
    return Optional.absent();
  }
}
