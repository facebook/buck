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

import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyLogFilePrinterTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private Path logPath;
  private RuleKeyLogFileReader reader = new RuleKeyLogFileReader();
  private List<FullRuleKey> ruleKeys = new ArrayList<>();
  private TestPrintStream stream = TestPrintStream.create();

  @Before
  public void setUp() throws InterruptedException, IOException {
    logPath = temporaryFolder.newFile("out.bin.log").toAbsolutePath();

    ruleKeys.add(new FullRuleKey("hash1", "//this/is/a:test", "rule_type", ImmutableMap.of()));
    ruleKeys.add(
        new FullRuleKey("hash2", "//this/is:another_test_rule", "rule_type", ImmutableMap.of()));
    ruleKeys.add(new FullRuleKey("hash3", "//some:rule", "rule_type", ImmutableMap.of()));
    ruleKeys.add(new FullRuleKey("hash3", "//:final_testing_rule", "rule_type", ImmutableMap.of()));

    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(logPath)) {
      for (FullRuleKey key : ruleKeys) {
        logger.write(key);
      }
    }
  }

  private void validateLines(FullRuleKey key, String line1, String line2, String line3) {
    Assert.assertEquals(
        String.format("%s:", Optional.ofNullable(key.name).orElse("Unknown Name")), line1);
    Assert.assertEquals(String.format("  key: %s", key.key), line2);
    Assert.assertEquals(String.format("  %s", key), line3);
  }

  @Test
  public void printsOutRegexMatchingRules() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(
            stream,
            reader,
            Optional.of(Pattern.compile("//.*test.*")),
            Optional.empty(),
            Integer.MAX_VALUE);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(9, lines.length);
    validateLines(ruleKeys.get(0), lines[0], lines[1], lines[2]);
    validateLines(ruleKeys.get(1), lines[3], lines[4], lines[5]);
    validateLines(ruleKeys.get(3), lines[6], lines[7], lines[8]);
  }

  @Test
  public void printsOutKeyMatchingRules() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(
            stream, reader, Optional.empty(), Optional.of("hash3"), Integer.MAX_VALUE);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(6, lines.length);
    validateLines(ruleKeys.get(2), lines[0], lines[1], lines[2]);
    validateLines(ruleKeys.get(3), lines[3], lines[4], lines[5]);
  }

  @Test
  public void printsOutLinesIndiscrimately() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(
            stream, reader, Optional.empty(), Optional.empty(), Integer.MAX_VALUE);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(12, lines.length);
    validateLines(ruleKeys.get(0), lines[0], lines[1], lines[2]);
    validateLines(ruleKeys.get(1), lines[3], lines[4], lines[5]);
    validateLines(ruleKeys.get(2), lines[6], lines[7], lines[8]);
    validateLines(ruleKeys.get(3), lines[9], lines[10], lines[11]);
  }

  @Test
  public void stopsPrintingWhenRegexMatchesMaxLines() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(
            stream, reader, Optional.of(Pattern.compile("//.*test.*")), Optional.empty(), 2);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(6, lines.length);
    validateLines(ruleKeys.get(0), lines[0], lines[1], lines[2]);
    validateLines(ruleKeys.get(1), lines[3], lines[4], lines[5]);
  }

  @Test
  public void stopsPrintingWhenKeyMatchesMaxLines() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(stream, reader, Optional.empty(), Optional.of("hash3"), 1);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(3, lines.length);
    validateLines(ruleKeys.get(2), lines[0], lines[1], lines[2]);
  }

  @Test
  public void stopsPrintingWhenNoFilterMatchesMaxLines() throws ParseException {
    RuleKeyLogFilePrinter printer =
        new RuleKeyLogFilePrinter(stream, reader, Optional.empty(), Optional.empty(), 2);
    printer.printFile(logPath);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(6, lines.length);
    validateLines(ruleKeys.get(0), lines[0], lines[1], lines[2]);
    validateLines(ruleKeys.get(1), lines[3], lines[4], lines[5]);
  }
}
