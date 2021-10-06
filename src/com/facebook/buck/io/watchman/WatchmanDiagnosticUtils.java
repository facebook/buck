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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** utils that is used to diagnosis watchman related issues. */
public class WatchmanDiagnosticUtils {
  private static final long DEFAULT_WATCHMAN_WATCHLIST_TIMEOUT_IN_MS = 20 * 1000;
  private static final Logger LOG = Logger.get(WatchmanDiagnosticUtils.class);

  /**
   * Run `watchman watch-list` and return its result. This util explicitly do not rely on
   * WatchmanClient created by buck
   */
  public static String runWatchmanWatchList() {
    String newline = System.getProperty("line.separator");
    StringBuilder sb = new StringBuilder();
    sb.append(newline);
    sb.append("Run `watchman watch-list`");
    Either<String, String> watchmanWatchListResult = runWatchmanWatchListInner();
    if (watchmanWatchListResult.isLeft()) {
      sb.append(watchmanWatchListResult);
    } else {
      String msg = "Failed to run `watchman watch-list`:" + watchmanWatchListResult.getRight();
      sb.append(msg);
      LOG.warn(msg);
    }
    return sb.toString();
  }

  private static Either<String, String> runWatchmanWatchListInner() {
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("watchman", "watch-list"))
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result;
    try {
      result =
          executor.launchAndExecute(
              processExecutorParams,
              options,
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.of(DEFAULT_WATCHMAN_WATCHLIST_TIMEOUT_IN_MS),
              /* timeOutHandler */ Optional.empty());
    } catch (Exception e) {
      return Either.ofRight(e.getMessage());
    }
    if (result.isTimedOut()) {
      return Either.ofRight("timed out");
    }

    if (result.getExitCode() != 0) {
      return Either.ofRight(result.getMessageForUnexpectedResult("Ran `watchman watch-list`"));
    }

    return Either.ofLeft(result.getStdout().get());
  }
}
