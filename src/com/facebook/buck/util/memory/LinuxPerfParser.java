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

package com.facebook.buck.util.memory;

import java.util.Optional;
import java.util.Scanner;

/** Parser for the output of perf stat (/usr/bin/perf). */
public class LinuxPerfParser {
  /**
   * Parses a {@link ResourceUsage} from the output of perf stat (/usr/bin/perf on Linux) systems.
   * Read from the output of perf stat -x " " -e instructions. From the perf man page: With -x, perf
   * stat is able to output a not-quite-CSV format output Commas in the output are not put into "".
   * To make it easy to parse it is recommended to use a different character like -x \;
   *
   * @param perfOutput Output of perf stat for a given process
   * @return A parsed {@link ResourceUsage} derived from the output of perf stat
   */
  public static ResourceUsage parse(String perfOutput) {
    try (Scanner sc = new Scanner(perfOutput)) {
      long instructionCount = 0;
      while (sc.hasNextLine()) {
        String line = sc.nextLine();
        if (line.startsWith("#") || line.isBlank()) {
          // Skip comments and empty lines
          continue;
        }
        String[] parts = line.split(";");
        if ("instructions:uP".equals(parts[2])) {
          try {
            instructionCount = Long.parseLong(parts[0]);
          } catch (NumberFormatException e) {
            // Some of these may be expected, e.g. platforms without hardware counters will
            // return <not supported> or <not counted>, but regardless, just pass and let
            // instructionCount be set to 0
            instructionCount = 0;
          }
        }
      }

      return ImmutableResourceUsage.ofImpl(Optional.empty(), Optional.of(instructionCount));
    }
  }
}
