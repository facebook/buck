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
 * Event for messages.  Post ConsoleEvents to the event bus where you would normally use
 * {@code java.util.logging}.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public class ConsoleEvent extends AbstractBuckEvent {

  private final Level level;
  private final String message;

  protected ConsoleEvent(Level level, String message) {
    this.level = level;
    this.message = message;
  }

  public Level getLevel() {
    return level;
  }

  public String getMessage() {
    return message;
  }

  public static ConsoleEvent create(Level level, String message, Object... args) {
    return new ConsoleEvent(level, String.format(message, args));
  }

  public static ConsoleEvent finer(String message, Object... args) {
    return ConsoleEvent.create(Level.FINER, message, args);
  }

  public static ConsoleEvent fine(String message, Object... args) {
    return ConsoleEvent.create(Level.FINE, message, args);
  }

  public static ConsoleEvent info(String message, Object... args) {
    return ConsoleEvent.create(Level.INFO, message, args);
  }

  public static ConsoleEvent warning(String message, Object... args) {
    return ConsoleEvent.create(Level.WARNING, message, args);
  }

  public static ConsoleEvent severe(String message, Object... args) {
    return ConsoleEvent.create(Level.SEVERE, message, args);
  }

  @Override
  public String getEventName() {
    return "ConsoleEvent";
  }

  @Override
  protected String getValueString() {
    return String.format("%s: %s", getLevel(), getMessage());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getLevel(), getMessage());
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof ConsoleEvent)) {
      return false;
    }

    ConsoleEvent that = (ConsoleEvent) event;

    return Objects.equal(getLevel(), that.getLevel()) &&
        Objects.equal(getMessage(), that.getMessage());
  }

  @Override
  public String toString() {
    return getMessage();
  }
}
