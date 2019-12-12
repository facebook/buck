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

package com.facebook.buck.tools.consistency;

import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Parses the output of `buck targets --show-target-hash` into something usable */
public class TargetHashFileParser {

  /** Simple information from parsing the targets file */
  static class ParsedTargetsFile {
    public final Path filename;
    public final BiMap<String, String> targetsToHash;
    public final Duration parseTime;

    ParsedTargetsFile(Path filename, BiMap<String, String> targetsToHash, Duration parseTime) {
      this.filename = filename;
      this.targetsToHash = targetsToHash;
      this.parseTime = parseTime;
    }
  }

  /**
   * Parses the output of `buck targets --show-target-hash` from a filename
   *
   * @param filename The file to parse
   * @return A parsed targets file
   * @throws ParseException If the file could not be read, is malformed, or has duplicate
   *     information within it
   */
  public ParsedTargetsFile parseFile(Path filename) throws ParseException {
    long startNanos = System.nanoTime();
    try (BufferedReader fileReader = Files.newBufferedReader(filename)) {
      int expectedSize = Math.toIntExact(Files.size(filename) / 150);
      HashBiMap<String, String> targetsToHash = HashBiMap.create(expectedSize);
      while (fileReader.ready()) {
        String line = fileReader.readLine();
        String[] parts = line.split(" ");
        if (parts.length != 2) {
          throw new ParseException(
              filename, "Lines must be of the format 'TARGET HASH'. Got %s", line);
        }
        if (targetsToHash.containsKey(parts[0])) {
          throw new ParseException(filename, "Target %s has been seen multiple times", parts[0]);
        }
        if (targetsToHash.containsValue(parts[1])) {
          throw new ParseException(
              filename,
              "Hash collision! Hash %s has been seen for both %s and %s!",
              parts[1],
              targetsToHash.inverse().get(parts[1]),
              parts[0]);
        }
        targetsToHash.put(parts[0], parts[1]);
      }
      Duration runtime = Duration.ofNanos(System.nanoTime() - startNanos);
      return new ParsedTargetsFile(filename, targetsToHash, runtime);
    } catch (IOException e) {
      throw new ParseException(e, filename, "Error reading file: %s", e.getMessage());
    }
  }
}
