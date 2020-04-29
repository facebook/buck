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

package com.facebook.buck.util;

import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link ProcessExecutor} interface implementation based on delegate {@link ProcessExecutor}
 * instance.
 */
public abstract class DelegateProcessExecutor implements ProcessExecutor {

  private final ProcessExecutor delegate;

  public DelegateProcessExecutor(ProcessExecutor processExecutor) {
    this.delegate = processExecutor;
  }

  @Override
  public Result waitForLaunchedProcess(LaunchedProcess launchedProcess)
      throws InterruptedException {
    return delegate.waitForLaunchedProcess(launchedProcess);
  }

  @Override
  public Result waitForLaunchedProcessWithTimeout(
      LaunchedProcess launchedProcess, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    return delegate.waitForLaunchedProcessWithTimeout(launchedProcess, millis, timeOutHandler);
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream stdOutStream, PrintStream stdErrStream) {
    return delegate.cloneWithOutputStreams(stdOutStream, stdErrStream);
  }

  @Override
  public Result execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    return delegate.execute(launchedProcess, options, stdin, timeOutMs, timeOutHandler);
  }

  protected ProcessExecutor getDelegate() {
    return delegate;
  }
}
