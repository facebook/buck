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

package com.facebook.buck.downwardapi.processexecutor;

import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Wrapper around {@lint ProcessExecutor.Result} that stores extra fields related to
 * {@DownwardApiProcessExecutor} that could be used in tests.
 */
class DownwardApiExecutionResult extends ProcessExecutor.Result {

  private final boolean isReaderThreadTerminated;

  public DownwardApiExecutionResult(
      int exitCode,
      boolean timedOut,
      Optional<String> stdout,
      Optional<String> stderr,
      ImmutableList<String> command,
      boolean isReaderThreadTerminated) {
    super(exitCode, timedOut, stdout, stderr, command);
    this.isReaderThreadTerminated = isReaderThreadTerminated;
  }

  @VisibleForTesting
  boolean isReaderThreadTerminated() {
    return isReaderThreadTerminated;
  }
}
