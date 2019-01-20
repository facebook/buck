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
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.tools.consistency.DifferState.MaxDifferencesException;
import com.facebook.buck.tools.consistency.RuleKeyDiffer.GraphTraversalException;
import com.facebook.buck.tools.consistency.RuleKeyLogFileReader.ParseException;
import com.facebook.buck.tools.consistency.RuleKeyStressRunner.RuleKeyStressRunException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleKeyStressRunnerTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  Callable<RuleKeyDiffer> differFactory;
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
          RuleKeyDiffPrinter ruleKeyPrinter = new RuleKeyDiffPrinter(diffPrinter, differState);
          RuleKeyDiffer differ = new RuleKeyDiffer(ruleKeyPrinter);
          return differ;
        };
  }

  @Test
  public void returnsReasonbleCommandsToRun() throws IOException, InterruptedException {
    binWriter.writeArgEchoer(0);
    TestPrintStream testStream1 = TestPrintStream.create();
    TestPrintStream testStream2 = TestPrintStream.create();

    List<String> expectedOutput1 =
        ImmutableList.of(
            System.getProperty("user.dir"),
            tempBinPath.toAbsolutePath().toString(),
            "targets",
            String.format("--rulekeys-log-path=%s", temporaryPaths.getRoot().resolve("0.bin.log")),
            "--show-rulekey",
            "--show-transitive-rulekeys",
            "@",
            "Random hashes configured",
            "Reading arguments from @",
            "//:test1",
            "//:test2");

    List<String> expectedOutput2 =
        ImmutableList.of(
            System.getProperty("user.dir"),
            tempBinPath.toAbsolutePath().toString(),
            "targets",
            String.format("--rulekeys-log-path=%s", temporaryPaths.getRoot().resolve("1.bin.log")),
            "--show-rulekey",
            "--show-transitive-rulekeys",
            "@",
            "Random hashes configured",
            "Reading arguments from @",
            "//:test1",
            "//:test2");

    RuleKeyStressRunner runner =
        new RuleKeyStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of(),
            ImmutableList.of("//:test1", "//:test2"));

    List<BuckRunner> commands =
        runner.getBuckRunners(2, temporaryPaths.getRoot(), Optional.empty());

    Assert.assertEquals(2, commands.size());

    Assert.assertEquals(0, commands.get(0).run(testStream1));
    Assert.assertEquals(0, commands.get(1).run(testStream2));
    assertAllStartWithPrefix(Arrays.asList(testStream1.getOutputLines()), expectedOutput1, 9);
    assertAllStartWithPrefix(Arrays.asList(testStream2.getOutputLines()), expectedOutput2, 9);
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
  public void throwsExceptionIfDifferencesFound()
      throws IOException, RuleKeyStressRunException, MaxDifferencesException,
          GraphTraversalException, ParseException {
    Path binLog1 = temporaryPaths.newFile("0.log.bin");
    Path binLog2 = temporaryPaths.newFile("1.log.bin");
    Path binLog3 = temporaryPaths.newFile("2.log.bin");

    expectedException.expect(RuleKeyStressRunException.class);
    expectedException.expectMessage(
        String.format("Found differences between %s and %s", binLog1, binLog3));

    FullRuleKey key1 =
        new FullRuleKey(
            "key1",
            "//:target1",
            "DEFAULT",
            ImmutableMap.of("value1", Value.ruleKeyHash(new RuleKeyHash("key3"))));
    FullRuleKey key2 =
        new FullRuleKey(
            "key2",
            "//:target1",
            "DEFAULT",
            ImmutableMap.of("value1", Value.ruleKeyHash(new RuleKeyHash("key4"))));
    FullRuleKey key3 =
        new FullRuleKey(
            "key3",
            "//:target3",
            "DEFAULT",
            ImmutableMap.of("value1", Value.stringValue("string2")));
    FullRuleKey key4 =
        new FullRuleKey(
            "key4",
            "//:target4",
            "DEFAULT",
            ImmutableMap.of("value1", Value.stringValue("string3")));

    try (ThriftRuleKeyLogger logger1 = ThriftRuleKeyLogger.create(binLog1);
        ThriftRuleKeyLogger logger2 = ThriftRuleKeyLogger.create(binLog2);
        ThriftRuleKeyLogger logger3 = ThriftRuleKeyLogger.create(binLog3)) {
      logger1.write(key1);
      logger1.write(key3);
      logger2.write(key1);
      logger2.write(key3);
      logger3.write(key2);
      logger3.write(key4);
    }

    RuleKeyStressRunner runner =
        new RuleKeyStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of(),
            ImmutableList.of("//:target1"));
    runner.verifyNoChanges(binLog1, ImmutableList.of(binLog2, binLog3));
  }

  @Test
  public void throwsErrorOnInvalidFiles()
      throws IOException, RuleKeyStressRunException, MaxDifferencesException,
          GraphTraversalException, ParseException {
    expectedException.expect(ParseException.class);

    Path binLog1 = temporaryPaths.newFile("0.log.bin");
    Path binLog2 = temporaryPaths.newFile("1.log.bin");
    FullRuleKey key = new FullRuleKey("key1", "//:target1", "DEFAULT", ImmutableMap.of());
    try (ThriftRuleKeyLogger logger = ThriftRuleKeyLogger.create(binLog1)) {
      logger.write(key);
    }

    RuleKeyStressRunner runner =
        new RuleKeyStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of(),
            ImmutableList.of("//:not_target1"));

    runner.verifyNoChanges(binLog1, ImmutableList.of(binLog2));
  }

  @Test
  public void runsSuccessfullyIfNoErrorsFound()
      throws RuleKeyStressRunException, MaxDifferencesException, GraphTraversalException,
          ParseException, IOException {
    Path binLog1 = temporaryPaths.newFile("0.log.bin");
    Path binLog2 = temporaryPaths.newFile("1.log.bin");
    Path binLog3 = temporaryPaths.newFile("2.log.bin");

    FullRuleKey key1 =
        new FullRuleKey(
            "key1",
            "//:target1",
            "DEFAULT",
            ImmutableMap.of("value1", Value.ruleKeyHash(new RuleKeyHash("key2"))));
    FullRuleKey key2 =
        new FullRuleKey(
            "key2",
            "//:target2",
            "DEFAULT",
            ImmutableMap.of("value1", Value.stringValue("string1")));

    try (ThriftRuleKeyLogger logger1 = ThriftRuleKeyLogger.create(binLog1);
        ThriftRuleKeyLogger logger2 = ThriftRuleKeyLogger.create(binLog2);
        ThriftRuleKeyLogger logger3 = ThriftRuleKeyLogger.create(binLog3)) {
      logger1.write(key1);
      logger1.write(key2);
      logger2.write(key1);
      logger2.write(key2);
      logger3.write(key1);
      logger3.write(key2);
    }

    RuleKeyStressRunner runner =
        new RuleKeyStressRunner(
            differFactory,
            Optional.of("python"),
            tempBinPath.toAbsolutePath().toString(),
            ImmutableList.of(),
            ImmutableList.of("//:target1"));
    runner.verifyNoChanges(binLog1, ImmutableList.of(binLog2, binLog3));
  }
}
