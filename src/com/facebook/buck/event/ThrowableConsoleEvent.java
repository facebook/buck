/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.event;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import java.util.IllegalFormatException;
import java.util.logging.Level;

/**
 * Event for tracking {@link Throwable}.
 *
 * <p>Deprecated, use {@link com.facebook.buck.util.ErrorLogger}.
 */
@Deprecated
public class ThrowableConsoleEvent extends ConsoleEvent {

  protected ThrowableConsoleEvent(Throwable throwable, String message) {
    this(throwable, Level.SEVERE, message);
  }

  protected ThrowableConsoleEvent(Throwable throwable, Level level, String message) {
    super(
        level, /* containsAnsiEscapeCodes */ false, combineThrowableAndMessage(throwable, message));
  }

  private static String combineThrowableAndMessage(Throwable throwable, String message) {
    String desc = message + System.lineSeparator() + throwable.getClass().getCanonicalName();
    if (throwable.getMessage() != null) {
      desc += ": " + throwable.getMessage();
    }
    desc += System.lineSeparator() + Throwables.getStackTraceAsString(throwable);
    return desc;
  }

  public static ThrowableConsoleEvent create(Throwable throwable, String message, Object... args) {
    String format;
    try {
      format = String.format(message, args);
    } catch (IllegalFormatException e) {
      format = "Malformed message: '" + message + "' args: [" + Joiner.on(",").join(args) + "]";
    }
    return new ThrowableConsoleEvent(throwable, format);
  }
}
