/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.tools.consistency;

import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Prints out the contents of a serialized rule key log file in a human friendly way */
public class RuleKeyLogFilePrinter {

  private final PrintStream outStream;
  private final Optional<Pattern> namesFilter;
  private final RuleKeyLogFileReader reader;
  private final Optional<String> keysFilter;
  private final int limit;

  /**
   * Creates an instance of {@link RuleKeyLogFilePrinter}
   *
   * @param outStream The stream to write to
   * @param reader A reader to get file contents from
   * @param namesFilter If provided, only print rules whose names match this regex
   * @param keysFilter If provided, only print rules whose key matches this string exactly
   * @param limit How many rules ot print out at most. Use {@link Integer.MAX_VALUE} for effectively
   *     unlimited rules.
   */
  public RuleKeyLogFilePrinter(
      PrintStream outStream,
      RuleKeyLogFileReader reader,
      Optional<Pattern> namesFilter,
      Optional<String> keysFilter,
      int limit) {
    this.outStream = outStream;
    this.reader = reader;
    this.namesFilter = namesFilter;
    this.keysFilter = keysFilter;
    this.limit = limit;
  }

  /**
   * Prints out a file to the given stream in a human friendly format
   *
   * @param filename The file containing length prefixed thrift compact serialized rule keys
   * @throws ParseException The file could not be read or parsed
   */
  public void printFile(Path filename) throws ParseException {
    final int[] linesVisited = {0};
    Consumer<FullRuleKey> filterLambda = ruleKey -> printRuleKey(linesVisited, ruleKey);
    if (namesFilter.isPresent()) {
      filterLambda =
          ruleKey -> {
            if (namesFilter.get().matcher(ruleKey.name != null ? ruleKey.name : "").matches()) {
              printRuleKey(linesVisited, ruleKey);
            }
          };
    } else if (keysFilter.isPresent()) {
      filterLambda =
          ruleKey -> {
            if (keysFilter.get().equals(ruleKey.key)) {
              printRuleKey(linesVisited, ruleKey);
            }
          };
    }
    Consumer<FullRuleKey> finalFilterLambda = filterLambda;
    reader.readFile(
        filename,
        ruleKey -> {
          finalFilterLambda.accept(ruleKey);
          return linesVisited[0] >= limit;
        });
  }

  private void printRuleKey(int visitCount[], FullRuleKey ruleKey) {
    outStream.println(
        String.format(
            "%s:%s  key: %s%s  %s",
            Optional.ofNullable(ruleKey.name).orElse("Unknown Name"),
            System.lineSeparator(),
            ruleKey.key,
            System.lineSeparator(),
            ruleKey));
    visitCount[0] += 1;
  }
}
