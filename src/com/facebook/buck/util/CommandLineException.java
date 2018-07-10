/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.core.exceptions.ExceptionWithHumanReadableMessage;
import com.facebook.buck.core.exceptions.HumanReadableException;
import javax.annotation.Nullable;

/**
 * Exception that is raised when user-supplied command line contains incompatible parameters or in
 * general cannot be executed
 */
public class CommandLineException extends HumanReadableException
    implements ExceptionWithHumanReadableMessage {

  public CommandLineException(String humanReadableFormatString, Object... args) {
    this(null, humanReadableFormatString, args);
  }

  public CommandLineException(String humanReadableErrorMessage) {
    this(null /* cause */, humanReadableErrorMessage);
  }

  public CommandLineException(ExceptionWithHumanReadableMessage e) {
    this((Throwable) ((e instanceof Throwable) ? e : null), e.getHumanReadableErrorMessage());
  }

  public CommandLineException(@Nullable Throwable cause, String humanReadableErrorMessage) {
    super(cause, formatMessage(humanReadableErrorMessage));
  }

  public CommandLineException(
      @Nullable Throwable cause, String humanReadableFormatString, Object... args) {
    super(cause, formatMessage(humanReadableFormatString, args));
  }

  private static String formatMessage(String message) {
    return "BAD ARGUMENTS: " + message;
  }

  private static String formatMessage(String format, Object... args) {
    return "BAD ARGUMENTS: " + String.format(format, args);
  }
}
