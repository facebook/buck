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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Utility class for logging useful system information. */
public class SystemInfoLogger {

  private static final Logger LOG = Logger.get(SystemInfoLogger.class);

  /** Log soft and hard file limits as returned by `ulimit`. */
  public static void logFileLimits() {
    if (!LOG.isDebugEnabled()) {
      return;
    }

    Platform hostPlatform = Platform.detect();
    if (hostPlatform != Platform.LINUX && hostPlatform != Platform.MACOS) {
      return;
    }

    try {
      List<String> softOutput = executeSystemCommand("ulimit", "-Sn");
      List<String> hardOutput = executeSystemCommand("ulimit", "-Hn");
      LOG.debug("Soft file limit: %s, hard file limit: %s", softOutput.get(0), hardOutput.get(0));
    } catch (Exception e) {
      LOG.warn(e, "Exception while trying to log file limits");
    }
  }

  /** Log the parent process chain leading to the invocation of this process. */
  public static void logParentProcessChain() {
    if (!LOG.isDebugEnabled()) {
      return;
    }

    Platform hostPlatform = Platform.detect();
    if (hostPlatform != Platform.LINUX && hostPlatform != Platform.MACOS) {
      return;
    }

    try {
      List<String> output = executeSystemCommand("ps", "-axo", "pid,ppid,command");

      Map<Integer, Integer> parentPids = new HashMap<Integer, Integer>();
      Map<Integer, String> commands = new HashMap<Integer, String>();

      for (int i = 1 /* skip header */; i < output.size(); i++) {
        String[] cols = output.get(i).trim().split("\\s+", 3);
        int pid = Integer.valueOf(cols[0]);
        int parentPid = Integer.valueOf(cols[1]);
        String command = cols[2];
        parentPids.put(pid, parentPid);
        commands.put(pid, command);
      }

      StringBuilder logString = new StringBuilder();
      logString.append("Parent process chain:\n");

      int pid = Libc.INSTANCE.getpid();
      while (pid != 0) {
        logString.append(String.format("%7d %s\n", pid, commands.get(pid)));
        if (!parentPids.containsKey(pid)) {
          break;
        }
        pid = Preconditions.checkNotNull(parentPids.get(pid));
      }

      LOG.debug(logString.toString());
    } catch (Exception e) {
      LOG.warn(e, "Exception while trying to log parent process chain");
    }
  }

  private static List<String> executeSystemCommand(String... args)
      throws InterruptedException, IOException {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    List<String> result = new ArrayList<String>();

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        result.add(line);
      }
    }

    int exitCode = process.waitFor();
    Preconditions.checkState(
        exitCode == 0, "command `%s` returned exit code %d", String.join(" ", args), exitCode);
    return result;
  }
}
