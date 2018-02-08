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
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.facebook.buck.tools.consistency.TargetsStressRunner.TargetsStressRunException;
import com.google.common.collect.ImmutableList;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TargetsStressRunnerTest {

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  Callable<TargetsDiffer> differFactory;
  TestPrintStream stream = TestPrintStream.create();
  private TestBinWriter binWriter;
  private Path tempBinPath;

  @Before
  public void setUp() throws IOException {
    tempBinPath = temporaryPaths.newFile("buck_bin.py");
    binWriter = new TestBinWriter(tempBinPath);
    differFactory =
        () -> {
          DifferState differState = new DifferState(DifferState.INFINITE_DIFFERENCES);
          DiffPrinter diffPrinter = new DiffPrinter(stream, false);
          TargetsDiffer differ = new TargetsDiffer(diffPrinter, differState);
          return differ;
        };
  }

  @Test
  public void getsCorrectBuckRunners() throws IOException, InterruptedException {
    TestPrintStream testStream1 = TestPrintStream.create();
    TestPrintStream testStream2 = TestPrintStream.create();
    binWriter.writeArgEchoer(0);
    TargetsStressRunner runner =
        new TargetsStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of("-c", "config=value"),
            ImmutableList.of("//:target1", "//:target2"));

    List<String> expectedCommand =
        ImmutableList.of(
            System.getProperty("user.dir"),
            tempBinPath.toAbsolutePath().toString(),
            "targets",
            "-c",
            "config=value",
            "--show-target-hash",
            "--show-transitive-target-hashes",
            "--target-hash-file-mode=PATHS_ONLY",
            "@",
            "Random hashes configured",
            "Reading arguments from @",
            "//:target1",
            "//:target2");

    List<BuckRunner> runners = runner.getBuckRunners(2, Optional.empty());

    Assert.assertEquals(2, runners.size());
    runners.get(0).run(testStream1);
    runners.get(1).run(testStream2);
    ImmutableList<String> outputLines1 = ImmutableList.copyOf(testStream1.getOutputLines());
    ImmutableList<String> outputLines2 = ImmutableList.copyOf(testStream2.getOutputLines());

    assertAllStartWithPrefix(outputLines1, expectedCommand, 11);
    assertAllStartWithPrefix(outputLines2, expectedCommand, 11);
  }

  private void assertAllStartWithPrefix(
      List<String> lines, List<String> expectedPrefixes, int randomStartIdx) {
    Assert.assertEquals(expectedPrefixes.size(), lines.size());
    int partition = randomStartIdx == -1 ? lines.size() : randomStartIdx;
    Assert.assertThat(
        lines.subList(0, partition),
        Matchers.contains(
            expectedPrefixes
                .subList(0, partition)
                .stream()
                .map(prefix -> Matchers.startsWith(prefix))
                .collect(ImmutableList.toImmutableList())));

    Assert.assertThat(
        lines.subList(partition, lines.size()),
        Matchers.containsInAnyOrder(
            expectedPrefixes
                .subList(partition, expectedPrefixes.size())
                .stream()
                .map(prefix -> Matchers.startsWith(prefix))
                .collect(ImmutableList.toImmutableList())));
  }

  @Test
  public void throwsExceptionIfDifferenceIsFound()
      throws IOException, ParseException, TargetsStressRunException, MaxDifferencesException {

    Path file1 = temporaryPaths.newFile("1");
    Path file2 = temporaryPaths.newFile("2");
    Path file3 = temporaryPaths.newFile("3");

    expectedException.expect(TargetsStressRunException.class);
    expectedException.expectMessage(
        String.format(
            "Found differences between %s and %s", file1.toAbsolutePath(), file3.toAbsolutePath()));

    try (BufferedWriter output1 = Files.newBufferedWriter(file1);
        BufferedWriter output2 = Files.newBufferedWriter(file2);
        BufferedWriter output3 = Files.newBufferedWriter(file3)) {
      output1.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output2.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output3.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output1.newLine();
      output2.newLine();
      output3.newLine();

      output1.write("//:target2 81643a1508128186137c6b03d13b6352d6c5dfaf");
      output2.write("//:target2 81643a1508128186137c6b03d13b6352d6c5dfaf");
      output3.write("//:target2 a59619898666a2d5b3e1669a4293301b799763c1");
      output1.newLine();
      output2.newLine();
      output3.newLine();
    }

    TargetsStressRunner runner =
        new TargetsStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of("-c", "config=value"),
            ImmutableList.of("//:target1", "//:target2"));
    runner.verifyNoChanges(file1, ImmutableList.of(file2, file3));
  }

  @Test
  public void returnsNormallyIfNoChanges()
      throws IOException, ParseException, TargetsStressRunException, MaxDifferencesException {

    Path file1 = temporaryPaths.newFile("1");
    Path file2 = temporaryPaths.newFile("2");
    Path file3 = temporaryPaths.newFile("3");

    try (BufferedWriter output1 = Files.newBufferedWriter(file1);
        BufferedWriter output2 = Files.newBufferedWriter(file2);
        BufferedWriter output3 = Files.newBufferedWriter(file3)) {
      output1.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output2.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output3.write("//:target1 32bbfed4bbbb971d9499b016df1d4358cc4ad4ba");
      output1.newLine();
      output2.newLine();
      output3.newLine();

      output1.write("//:target2 81643a1508128186137c6b03d13b6352d6c5dfaf");
      output2.write("//:target2 81643a1508128186137c6b03d13b6352d6c5dfaf");
      output3.write("//:target2 81643a1508128186137c6b03d13b6352d6c5dfaf");
      output1.newLine();
      output2.newLine();
      output3.newLine();
    }

    TargetsStressRunner runner =
        new TargetsStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of("-c", "config=value"),
            ImmutableList.of("//:target1", "//:target2"));
    runner.verifyNoChanges(file1, ImmutableList.of(file2, file3));
  }
}
