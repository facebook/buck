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

package com.facebook.buck.logd;

import com.facebook.buck.core.exceptions.HumanReadableException;
import javax.annotation.Nullable;

/** Represents error during LogD service */
public class LogDaemonException extends HumanReadableException {

  public LogDaemonException(String humanReadableMessage) {
    super(humanReadableMessage);
  }

  public LogDaemonException(
      @Nullable Throwable cause, String humanReadableFormatString, Object... args) {
    super(cause, humanReadableFormatString, args);
  }
}
