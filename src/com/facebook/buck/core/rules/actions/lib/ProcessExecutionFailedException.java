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

package com.facebook.buck.core.rules.actions.lib;

import com.facebook.buck.core.exceptions.HumanReadableException;

/**
 * Exception that indicates that process execution failed. Can be used to print to the user what the
 * exit code of an unsuccessful action was
 */
public class ProcessExecutionFailedException extends HumanReadableException {
  public ProcessExecutionFailedException(int exitCode) {
    super("Process exited with code %d", exitCode);
  }
}
