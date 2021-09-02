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

import java.util.Scanner;

/** Parser for the output of GNU Time (/usr/bin/time). */
class LinuxTimeParser {
  /**
   * Parses a {@link ResourceUsage} from the output of GNU Time (/usr/bin/time on Linux) systems.
   * Since GNU Time allows for users to specify arbitrary format strings to it that it will use to
   * format its output, this class expects a specific format: repeated lines of the form: <code>
   * string: number</code> where number is an arbitrary integer and string is the name of the metric
   * being reported by GNU Time.
   *
   * @param timeOutput Output of GNU Time for a given process
   * @return A parsed {@link ResourceUsage} derived from the output of GNU Time
   */
  ResourceUsage parse(String timeOutput) {
    try (Scanner sc = new Scanner(timeOutput)) {
      long maxRss = 0;
      while (sc.hasNextLine()) {
        String line = sc.nextLine();
        String[] parts = line.split(" ");
        if (parts.length == 2) {
          switch (parts[0]) {
            case "rss":
              maxRss = Integer.parseInt(parts[1]);
              break;
            default:
              break;
          }
        }
      }

      return ImmutableResourceUsage.ofImpl(maxRss);
    }
  }
}
