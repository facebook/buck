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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.List;
import java.util.concurrent.Callable;

public interface CommandRunner {

  /**
   * Note that this method blocks until the specified command terminates.
   */
  public void runCommand(Command command) throws CommandFailedException;

  /**
   * In a new thread, executes of the list of commands and then invokes {@code interpretResults} to
   * return a value that represents the output of the commands.
   */
  public <T> ListenableFuture<T> runCommandsAndYieldResult(
      List<Command> commands, Callable<T> interpretResults);

  public ListeningExecutorService getListeningExecutorService();
}
