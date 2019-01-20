/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.util.ExitCode;
import java.util.Optional;

/** Whether and when to upload logs. */
public enum LogUploadMode {
  NEVER,
  ONLY_ON_ERROR,
  ALWAYS,
  ;

  /** Depending on the exit code, returns whether the log should be uploaded. */
  public boolean shouldUploadLogs(Optional<ExitCode> commandExitCode) {
    switch (this) {
      case NEVER:
        return false;
      case ONLY_ON_ERROR:
        return !commandExitCode.isPresent() || commandExitCode.get() != ExitCode.SUCCESS;
      case ALWAYS:
        return true;
    }
    return false;
  }
}
