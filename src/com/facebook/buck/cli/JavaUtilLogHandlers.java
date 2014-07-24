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
      }
    }
    return Optional.absent();
  }
}
