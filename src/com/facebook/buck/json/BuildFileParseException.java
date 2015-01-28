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

package com.facebook.buck.json;

import com.facebook.buck.util.ExceptionWithHumanReadableMessage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Thrown if we encounter an unexpected, fatal condition while interacting with the
 * build file parser.
 */
@SuppressWarnings("serial")
public class BuildFileParseException extends Exception
    implements ExceptionWithHumanReadableMessage {

  private BuildFileParseException(String message) {
    super(message);
  }

  static BuildFileParseException createForUnknownParseError(String message) {
    return new BuildFileParseException(message);
  }

  private static String formatMessageWithCause(String message, IOException cause) {
    if (cause != null && cause.getMessage() != null) {
      return message + ": " + cause.getMessage();
    } else {
      return message;
    }
  }

  static BuildFileParseException createForBuildFileParseError(Path buildFile,
      IOException cause) {
    String message = String.format("Parse error for build file %s",
        buildFile);
    return new BuildFileParseException(formatMessageWithCause(message, cause));
  }

  @Override
  public String getHumanReadableErrorMessage() {
    return getMessage();
  }
}
