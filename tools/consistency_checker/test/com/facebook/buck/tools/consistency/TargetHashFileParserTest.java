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

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.facebook.buck.tools.consistency.TargetHashFileParser.ParsedTargetsFile;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TargetHashFileParserTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private Path logPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    logPath = temporaryFolder.newFile("out.bin.log").toAbsolutePath();
  }

  private void writeLines(String... lines) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(logPath)) {
      for (String line : lines) {
        writer.write(line);
        writer.newLine();
      }
    }
  }

  @Test
  public void failsIfFileDoesNotExist() throws IOException, ParseException {
    Path invalidLogPath = logPath.resolveSibling("invalid_path");
    expectedException.expectMessage(String.format("%s: Error reading file:", invalidLogPath));
    expectedException.expect(ParseException.class);
    TargetHashFileParser parser = new TargetHashFileParser();
    parser.parseFile(invalidLogPath);
  }

  @Test
  public void failsIfLineCannotBeSplit() throws IOException, ParseException {
    expectedException.expectMessage(
        "Lines must be of the format 'TARGET HASH'. Got Invalid line goes here");
    expectedException.expect(ParseException.class);

    writeLines(
        "//:target1 d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
        "//:target2 0340a9686229b00c43642d9f8dd07e313ee6c8ba",
        "Invalid line goes here");

    TargetHashFileParser parser = new TargetHashFileParser();
    parser.parseFile(logPath);
  }

  @Test
  public void failsIfDuplicateHashFound() throws IOException, ParseException {
    expectedException.expectMessage(
        "Hash collision! Hash 927bf4f375b2167c93e8219d5d50056f69019dfb has been seen for both //:target3 and //:target4!");
    expectedException.expect(ParseException.class);

    writeLines(
        "//:target1 d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
        "//:target2 0340a9686229b00c43642d9f8dd07e313ee6c8ba",
        "//:target3 927bf4f375b2167c93e8219d5d50056f69019dfb",
        "//:target4 927bf4f375b2167c93e8219d5d50056f69019dfb",
        "//:target5 d48528295847c0bb6856e6f62c2872f9d8e44b9d");

    TargetHashFileParser parser = new TargetHashFileParser();
    parser.parseFile(logPath);
  }

  @Test
  public void failsIfDuplicateTargetFound() throws IOException, ParseException {
    expectedException.expectMessage("Target //:target_dupe has been seen multiple times");
    expectedException.expect(ParseException.class);

    writeLines(
        "//:target1 d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
        "//:target2 0340a9686229b00c43642d9f8dd07e313ee6c8ba",
        "//:target_dupe 927bf4f375b2167c93e8219d5d50056f69019dfb",
        "//:target_dupe 2e943f1a14f1190868391ae0e4d660d01457271b",
        "//:target5 d48528295847c0bb6856e6f62c2872f9d8e44b9d");

    TargetHashFileParser parser = new TargetHashFileParser();
    parser.parseFile(logPath);
  }

  @Test
  public void returnsProperlyParsedTargetsFile() throws IOException, ParseException {
    writeLines(
        "//:target1 d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
        "//:target2 0340a9686229b00c43642d9f8dd07e313ee6c8ba",
        "//:target3 927bf4f375b2167c93e8219d5d50056f69019dfb",
        "//:target4 2e943f1a14f1190868391ae0e4d660d01457271b",
        "//:target5 d48528295847c0bb6856e6f62c2872f9d8e44b9d");

    TargetHashFileParser parser = new TargetHashFileParser();
    ParsedTargetsFile parsedFile = parser.parseFile(logPath);

    Assert.assertEquals(logPath, parsedFile.filename);
    Assert.assertEquals(5, parsedFile.targetsToHash.size());
    Assert.assertTrue(parsedFile.parseTime.toNanos() > 0);
    Assert.assertEquals(
        "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f", parsedFile.targetsToHash.get("//:target1"));
    Assert.assertEquals(
        "0340a9686229b00c43642d9f8dd07e313ee6c8ba", parsedFile.targetsToHash.get("//:target2"));
    Assert.assertEquals(
        "927bf4f375b2167c93e8219d5d50056f69019dfb", parsedFile.targetsToHash.get("//:target3"));
    Assert.assertEquals(
        "2e943f1a14f1190868391ae0e4d660d01457271b", parsedFile.targetsToHash.get("//:target4"));
    Assert.assertEquals(
        "d48528295847c0bb6856e6f62c2872f9d8e44b9d", parsedFile.targetsToHash.get("//:target5"));
  }
}
