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

package com.facebook.buck.shell;

import com.facebook.buck.debug.Tracer;
import com.facebook.buck.util.MoreFutures;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public final class DefaultCommandRunner implements CommandRunner {

  private static final String TRACER_COMMENT_TYPE = "COMMAND";

  private final ExecutionContext context;
  private final ListeningExecutorService listeningExecutorService;

  /**
   * This CommandRunner will run all commands on the same thread.
   */
  public DefaultCommandRunner(ExecutionContext context) {
    this(context, MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1)));
  }

  public DefaultCommandRunner(ExecutionContext context,
      ListeningExecutorService listeningExecutorService) {
    this.context = Preconditions.checkNotNull(context);
    this.listeningExecutorService = Preconditions.checkNotNull(listeningExecutorService);
  }

  @Override
  public ListeningExecutorService getListeningExecutorService() {
    return listeningExecutorService;
  }

  @Override
  public void runCommand(final Command command) throws CommandFailedException {
    Preconditions.checkNotNull(command);

    if (context.getVerbosity().shouldPrintCommand()) {
      context.getStdErr().println(command.getDescription(context));
    }

    int exitCode = command.execute(context);
    if (exitCode != 0) {
      throw new CommandFailedException(command, context, exitCode);
    }

    Tracer.addComment(command.getShortName(context), TRACER_COMMENT_TYPE);
  }

  @Override
  public <T> ListenableFuture<T> runCommandsAndYieldResult(final List<Command> commands,
      final Callable<T> interpretResults) {
    Callable<T> callable = new Callable<T>() {

      @Override
      public T call() throws Exception {
        for (Command command : commands) {
          runCommand(command);
        }

        return interpretResults.call();
      }

    };

    return listeningExecutorService.submit(callable);
  }

  /**
   * Run multiple commands in parallel and block waiting for all of them to finish.  An
   * exception is thrown (immediately) if any command fails.
   *
   * @param commands List of commands to execute.
   */
  public void runCommandsInParallelAndWait(final List<Command> commands)
      throws CommandFailedException {
    List<Callable<Void>> callables = Lists.transform(commands,
        new Function<Command, Callable<Void>>() {
      @Override
      public Callable<Void> apply(final Command command) {
        return new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            runCommand(command);
            return null;
          }
        };
      }
    });

    try {
      MoreFutures.getAllUninterruptibly(getListeningExecutorService(), callables);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, CommandFailedException.class);

      // Programmer error.  Boo-urns.
      throw new RuntimeException(cause);
    }
  }
}
