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

import com.google.common.base.Objects;

import java.util.logging.Level;

/**
 * Event for tracking {@link Throwable}
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public class ThrowableConsoleEvent extends ConsoleEvent {

  private final Throwable throwable;

  protected ThrowableConsoleEvent(Throwable throwable, String message) {
    this(throwable, Level.SEVERE, message);
  }

  protected ThrowableConsoleEvent(Throwable throwable, Level level, String message) {
    super(level, combineThrowableAndMessage(throwable, message));
    this.throwable = throwable;
  }

  private static String combineThrowableAndMessage(Throwable throwable, String message) {
    if (throwable.getMessage() == null) {
      return message;
    }
    return message + "\n" + throwable.getMessage();
  }

  public Throwable getThrowable() {
    return throwable;
  }

  public static ThrowableConsoleEvent create(Throwable throwable, String message, Object... args) {
    return new ThrowableConsoleEvent(throwable, String.format(message, args));
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof ThrowableConsoleEvent)) {
      return false;
    }

    ThrowableConsoleEvent that = (ThrowableConsoleEvent) event;

    return super.isRelatedTo(that) &&
        Objects.equal(getThrowable(), that.getThrowable());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getMessage(), getLevel(), getThrowable());
  }
}
