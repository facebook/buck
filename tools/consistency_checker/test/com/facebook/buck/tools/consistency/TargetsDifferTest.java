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
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.TargetHashFileParser.ParsedTargetsFile;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TargetsDifferTest {

  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private String logFile1;
  private String logFile2;
  private TestPrintStream stream;
  private DiffPrinter diffPrinter;

  @Before
  public void setUp() throws InterruptedException, IOException {
    stream = TestPrintStream.create();
    diffPrinter = new DiffPrinter(stream, false);
  }

  @Test
  public void printsChangesProperly() throws Exception {
    Map<String, String> originalFileContents =
        ImmutableMap.of(
            "//:target1", "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
            "//:target2", "0340a9686229b00c43642d9f8dd07e313ee6c8ba",
            "//:target3", "927bf4f375b2167c93e8219d5d50056f69019dfb",
            "//:target4", "fd680e95d150768e97ea46ede62c596aa91929ca",
            "//:target5", "d48528295847c0bb6856e6f62c2872f9d8e44b9d");
    Map<String, String> newFileContents =
        ImmutableMap.of(
            "//:target3", "927bf4f375b2167c93e8219d5d50056f69019dfb",
            "//:target4", "2e943f1a14f1190868391ae0e4d660d01457271b",
            "//:target5", "d48528295847c0bb6856e6f62c2872f9d8e44b9d",
            "//:target6", "43375a4d4b961d4ddc40e49b40d67ad368cd30ab",
            "//:target7", "686e426f0440da68d9ccd544427e415d0953113f");
    ParsedTargetsFile originalFile =
        new ParsedTargetsFile(
            Paths.get("file1"), HashBiMap.create(originalFileContents), Duration.ofNanos(1));
    ParsedTargetsFile newFile =
        new ParsedTargetsFile(
            Paths.get("file2"), HashBiMap.create(newFileContents), Duration.ofNanos(1));

    DifferState differState = new DifferState(DifferState.INFINITE_DIFFERENCES);
    TargetsDiffer targetsDiffer = new TargetsDiffer(diffPrinter, differState);

    targetsDiffer.printDiff(originalFile, newFile);

    String[] lines = stream.getOutputLines();
    Assert.assertEquals(6, lines.length);
    Assert.assertEquals("- //:target1 d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f", lines[0]);
    Assert.assertEquals("- //:target2 0340a9686229b00c43642d9f8dd07e313ee6c8ba", lines[1]);
    Assert.assertEquals("+ //:target6 43375a4d4b961d4ddc40e49b40d67ad368cd30ab", lines[2]);
    Assert.assertEquals("+ //:target7 686e426f0440da68d9ccd544427e415d0953113f", lines[3]);
    Assert.assertEquals("- //:target4 fd680e95d150768e97ea46ede62c596aa91929ca", lines[4]);
    Assert.assertEquals("+ //:target4 2e943f1a14f1190868391ae0e4d660d01457271b", lines[5]);
  }

  @Test
  public void throwsExceptionOnAddIfMaxDiffsFound() throws MaxDifferencesException {
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    expectedThrownException.expect(MaxDifferencesException.class);

    Map<String, String> originalFileContents =
        ImmutableMap.of(
            "//:target1", "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
            "//:target2", "0340a9686229b00c43642d9f8dd07e313ee6c8ba");
    Map<String, String> newFileContents =
        ImmutableMap.of(
            "//:target1", "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
            "//:target2", "0340a9686229b00c43642d9f8dd07e313ee6c8ba",
            "//:target3", "927bf4f375b2167c93e8219d5d50056f69019dfb",
            "//:target4", "2e943f1a14f1190868391ae0e4d660d01457271b",
            "//:target5", "d48528295847c0bb6856e6f62c2872f9d8e44b9d");
    ParsedTargetsFile originalFile =
        new ParsedTargetsFile(
            Paths.get("file1"), HashBiMap.create(originalFileContents), Duration.ofNanos(1));
    ParsedTargetsFile newFile =
        new ParsedTargetsFile(
            Paths.get("file2"), HashBiMap.create(newFileContents), Duration.ofNanos(1));

    DifferState differState = new DifferState(2);
    TargetsDiffer targetsDiffer = new TargetsDiffer(diffPrinter, differState);

    targetsDiffer.printDiff(originalFile, newFile);
  }

  @Test
  public void throwsExceptionOnRemoveIfMaxDiffsFound() throws MaxDifferencesException {
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    expectedThrownException.expect(MaxDifferencesException.class);

    Map<String, String> originalFileContents =
        ImmutableMap.of(
            "//:target1", "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
            "//:target2", "0340a9686229b00c43642d9f8dd07e313ee6c8ba",
            "//:target3", "927bf4f375b2167c93e8219d5d50056f69019dfb",
            "//:target4", "2e943f1a14f1190868391ae0e4d660d01457271b",
            "//:target5", "d48528295847c0bb6856e6f62c2872f9d8e44b9d");
    Map<String, String> newFileContents =
        ImmutableMap.of(
            "//:target4", "2e943f1a14f1190868391ae0e4d660d01457271b",
            "//:target5", "d48528295847c0bb6856e6f62c2872f9d8e44b9d");
    ParsedTargetsFile originalFile =
        new ParsedTargetsFile(
            Paths.get("file1"), HashBiMap.create(originalFileContents), Duration.ofNanos(1));
    ParsedTargetsFile newFile =
        new ParsedTargetsFile(
            Paths.get("file2"), HashBiMap.create(newFileContents), Duration.ofNanos(1));

    DifferState differState = new DifferState(2);
    TargetsDiffer targetsDiffer = new TargetsDiffer(diffPrinter, differState);

    targetsDiffer.printDiff(originalFile, newFile);
  }

  @Test
  public void throwsExceptionOnChangeIfMaxDiffsFound() throws MaxDifferencesException {
    expectedThrownException.expectMessage("Stopping after finding 2 differences");
    expectedThrownException.expect(MaxDifferencesException.class);

    Map<String, String> originalFileContents =
        ImmutableMap.of(
            "//:target1", "d70cf68c1773e1c9882e4e3e9c7e9dc06173f82f",
            "//:target2", "0340a9686229b00c43642d9f8dd07e313ee6c8ba",
            "//:target3", "927bf4f375b2167c93e8219d5d50056f69019dfb");
    Map<String, String> newFileContents =
        ImmutableMap.of(
            "//:target1", "790b0301cacdda54534d04ca0fd2939dd6cf2793",
            "//:target2", "b1b4c9f27ee6624eff429517860287b0560cf43d",
            "//:target3", "d1031ce5571dc3a241223560a95d0f3bbb6fbd1d");
    ParsedTargetsFile originalFile =
        new ParsedTargetsFile(
            Paths.get("file1"), HashBiMap.create(originalFileContents), Duration.ofNanos(1));
    ParsedTargetsFile newFile =
        new ParsedTargetsFile(
            Paths.get("file2"), HashBiMap.create(newFileContents), Duration.ofNanos(1));

    DifferState differState = new DifferState(2);
    TargetsDiffer targetsDiffer = new TargetsDiffer(diffPrinter, differState);

    targetsDiffer.printDiff(originalFile, newFile);
  }
}
