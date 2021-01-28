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

import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** ProcessExecutor with an additional context */
public class ContextualProcessExecutor extends DelegateProcessExecutor {

  public static final String ANSI_ESCAPE_SEQUENCES_ENABLED = "ansi_escape_sequences_enabled";
  public static final String VERBOSITY = "verbosity";

  private final ImmutableMap<String, String> context;

  public ContextualProcessExecutor(ProcessExecutor delegate, ImmutableMap<String, String> context) {
    super(delegate);
    this.context = context;
  }

  @Override
  public LaunchedProcess launchProcess(ProcessExecutorParams params) throws IOException {
    return getDelegate().launchProcess(params, context);
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {
    return getDelegate().launchProcess(params, MoreMaps.merge(this.context, context));
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params)
      throws InterruptedException, IOException {
    return getDelegate().launchAndExecute(params, context);
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws InterruptedException, IOException {
    return getDelegate().launchAndExecute(params, MoreMaps.merge(this.context, context));
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return getDelegate()
        .launchAndExecute(params, context, options, stdin, timeOutMs, timeOutHandler);
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      ImmutableMap<String, String> context,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return getDelegate()
        .launchAndExecute(
            params,
            MoreMaps.merge(this.context, context),
            options,
            stdin,
            timeOutMs,
            timeOutHandler);
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream newStdOutStream, PrintStream newStdErrStream) {
    return new ContextualProcessExecutor(
        getDelegate().cloneWithOutputStreams(newStdOutStream, newStdErrStream), context);
  }

  @Override
  public ProcessExecutor withDownwardAPI(
      DownwardApiProcessExecutorFactory factory,
      NamedPipeEventHandlerFactory namedPipeEventHandlerFactory,
      IsolatedEventBus buckEventBus,
      String actionId,
      Clock clock) {
    String ansiEnabled = context.get(ANSI_ESCAPE_SEQUENCES_ENABLED);
    Preconditions.checkNotNull(ansiEnabled, ANSI_ESCAPE_SEQUENCES_ENABLED + " key is not provided");
    boolean ansiEscapeSequencesEnabled = Boolean.parseBoolean(ansiEnabled);

    String verbosityString = context.get(VERBOSITY);
    Preconditions.checkNotNull(verbosityString, VERBOSITY + " key is not provided");
    Verbosity verbosity = Verbosity.valueOf(verbosityString);

    return factory.create(
        this,
        namedPipeEventHandlerFactory,
        ConsoleParams.of(ansiEscapeSequencesEnabled, verbosity),
        buckEventBus,
        actionId,
        clock);
  }
}
