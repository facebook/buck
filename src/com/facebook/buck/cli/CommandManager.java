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

package com.facebook.buck.cli;

import com.facebook.buck.support.state.BuckGlobalState;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** The semaphor around commands so that we don't execute multiple conflicting commands */
interface CommandManager {
  /**
   * Try to acquire global semaphore if needed to do so. Attach closer to acquired semaphore in a
   * form of a wrapper object so it can be used with try-with-resources.
   *
   * @return (semaphore, previous args) If we successfully acquire the semaphore, return (semaphore,
   *     null). If there is already a command running but the command to run is readonly, return
   *     (null, null) and allow the execution. Otherwise, return (null, previously running command
   *     args) and block this command.
   */
  @Nullable
  CloseableWrapper<Unit> getSemaphoreWrapper(
      BuckCommand command,
      ImmutableList<String> currentArgs,
      ImmutableList.Builder<String> previousArgs);

  /**
   * registers that the current command is running with the given global state, which allows the
   * management of attaching listeners, etc.
   */
  void registerGlobalState(BuckGlobalState buckGlobalState);

  /**
   * Non-blocking implementation of command manager for when there's no daemon, which means that
   * multiple commands should never get ran at the same time on the same process.
   */
  class DefaultCommandManager implements CommandManager {

    @Nullable
    @Override
    public CloseableWrapper<Unit> getSemaphoreWrapper(
        BuckCommand command,
        ImmutableList<String> currentArgs,
        ImmutableList.Builder<String> previousArgs) {
      // we can execute read-only commands (query, targets, etc) in parallel
      if (command.isReadOnly()) {
        // using nullable instead of Optional<> to use the object with try-with-resources
        return null;
      }

      return CloseableWrapper.of(
          Unit.UNIT,
          unit -> {
            // TODO(buck_team): have background process killer have its own lifetime management
            BgProcessKiller.disarm();
          });
    }

    @Override
    public void registerGlobalState(BuckGlobalState buckGlobalState) {
      // nothing to do for non-daemon case
    }
  }
}
