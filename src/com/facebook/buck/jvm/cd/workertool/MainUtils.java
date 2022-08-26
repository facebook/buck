/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.cd.workertool;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.unit.SizeUnit;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Shared utilities for the compiler daemon entry points. */
public class MainUtils {
  private static final Logger LOG = Logger.get(MainUtils.class);

  private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

  public static void logCurrentCDState() {
    PerfStatsTracking.MemoryPerfStatsEvent memory = PerfStatsTracking.getMemoryPerfStatsEvent();
    long totalMemoryBytes = memory.getTotalMemoryBytes();
    long freeMemoryBytes = memory.getFreeMemoryBytes();
    long usedMemory = SizeUnit.BYTES.toMegabytes(totalMemoryBytes - freeMemoryBytes);
    long freeMemory = SizeUnit.BYTES.toMegabytes(freeMemoryBytes);
    long totalMemory = SizeUnit.BYTES.toMegabytes(totalMemoryBytes);
    long maxMemory = SizeUnit.BYTES.toMegabytes(memory.getMaxMemoryBytes());
    long timeSpendInGc = TimeUnit.MILLISECONDS.toSeconds(memory.getTimeSpentInGcMs());
    String pools =
        memory.getCurrentMemoryBytesUsageByPool().entrySet().stream()
            .map(e -> e.getKey() + "=" + SizeUnit.BYTES.toMegabytes(e.getValue()))
            .collect(Collectors.joining(", "));

    LOG.info(
        "CD state: executing tasks: %s, completed tasks: %s, largest pool size: %s, task count: %s. Available processors: %s, Time spend in GC: %s seconds, "
            + "Used Memory: %s, Free Memory: %s, Total Memory: %s, Max Memory: %s, Pools: %s",
        0,
        0,
        0,
        0,
        AVAILABLE_PROCESSORS,
        timeSpendInGc,
        usedMemory,
        freeMemory,
        totalMemory,
        maxMemory,
        pools);
  }

  public static void handleExceptionAndTerminate(
      Thread thread, Console console, Throwable throwable) {
    // Remove an existing `ExternalLogHandler` handler that depend on the closed event pipe stream.
    Logger logger = Logger.get("");
    logger.cleanHandlers();

    String errorMessage = ErrorLogger.getUserFriendlyMessage(throwable);
    // this method logs the message with log.warn that would be noop as all logger handlers have
    // been cleaned and prints the message into a std err.
    console.printErrorText(
        "Failed to execute compilation action. Thread: "
            + thread
            + System.lineSeparator()
            + errorMessage);
    System.exit(1);
  }
}
