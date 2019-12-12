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

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class ContextualProcessExecutor implements ProcessExecutor {

  private final ProcessExecutor delegate;
  private final ImmutableMap<String, String> context;

  public ContextualProcessExecutor(ProcessExecutor delegate, ImmutableMap<String, String> context) {
    this.delegate = delegate;
    this.context = context;
  }

  public ImmutableMap<String, String> getContext() {
    return context;
  }

  public ProcessExecutor getDelegate() {
    return delegate;
  }

  @Override
  public LaunchedProcess launchProcess(ProcessExecutorParams params) throws IOException {
    return delegate.launchProcess(params, context);
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {
    return delegate.launchProcess(params, MoreMaps.merge(this.context, context));
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params)
      throws InterruptedException, IOException {
    return delegate.launchAndExecute(params, context);
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws InterruptedException, IOException {
    return delegate.launchAndExecute(params, MoreMaps.merge(this.context, context));
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return delegate.launchAndExecute(params, context, options, stdin, timeOutMs, timeOutHandler);
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      ImmutableMap<String, String> context,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return delegate.launchAndExecute(
        params, MoreMaps.merge(this.context, context), options, stdin, timeOutMs, timeOutHandler);
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
  public void destroyLaunchedProcess(LaunchedProcess launchedProcess) {
    delegate.destroyLaunchedProcess(launchedProcess);
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream newStdOutStream, PrintStream newStdErrStream) {
    return new ContextualProcessExecutor(
        delegate.cloneWithOutputStreams(newStdOutStream, newStdErrStream), context);
  }
}
