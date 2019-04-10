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
package com.facebook.buck.distributed.build_client;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.LogLineBatch;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.LogStreamType;
import com.facebook.buck.distributed.thrift.SlaveStream;
import com.facebook.buck.distributed.thrift.StreamLogs;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LogStateTrackerTest {
  @Rule public TemporaryFolder projectDir = new TemporaryFolder();

  private static final String LOG_DIR = "logs";

  private static final String RUN_ONE_ID = "buildSlaveRunIdOne";
  private static final String RUN_TWO_ID = "buildSlaveRunIdTwo";

  /*
   **********************************
   * Log streaming test data
   **********************************
   */

  private static final String RUN_ONE_STD_ERR_LOG =
      "dist-build-slave-buildSlaveRunIdOne/STDERR.log";
  private static final String RUN_ONE_STD_OUT_LOG =
      "dist-build-slave-buildSlaveRunIdOne/STDOUT.log";
  private static final String RUN_TWO_STD_OUT_LOG =
      "dist-build-slave-buildSlaveRunIdTwo/STDOUT.log";

  private Path logDir;
  private LogStateTracker distBuildLogStateTracker;

  /*
   **********************************
   * Tests
   **********************************
   */

  @Before
  public void setUp() {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(projectDir.getRoot().toPath());
    DistBuildService service = EasyMock.createMock(DistBuildService.class);
    logDir = projectDir.getRoot().toPath().resolve(LOG_DIR);
    distBuildLogStateTracker = new LogStateTracker(logDir, projectFilesystem, service);
  }

  @Test
  public void testStreamsLogsForRuns() throws IOException {
    BuildSlaveInfo runOneSlaveInfo = new BuildSlaveInfo();
    BuildSlaveRunId runOneId = new BuildSlaveRunId();
    runOneId.setId(RUN_ONE_ID);
    runOneSlaveInfo.setBuildSlaveRunId(runOneId);

    BuildSlaveInfo runTwoSlaveInfo = new BuildSlaveInfo();
    BuildSlaveRunId runTwoId = new BuildSlaveRunId();
    runTwoId.setId(RUN_TWO_ID);
    runTwoSlaveInfo.setBuildSlaveRunId(runTwoId);

    // runOne has stdErr, and runTwo has stdOut to download.
    List<BuildSlaveInfo> buildSlaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);
    List<LogLineBatchRequest> requestsOne =
        distBuildLogStateTracker.createStreamLogRequests(buildSlaveInfos);

    assertThat(requestsOne.size(), Matchers.equalTo(4));

    // Request runOne/stdErr from batch 1
    assertTrue(
        requestsOne.stream()
            .anyMatch(
                r ->
                    r.slaveStream.buildSlaveRunId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDERR)
                        && r.batchNumber == 0));
    // Request runTwo/stdOut from batch 1
    assertTrue(
        requestsOne.stream()
            .anyMatch(
                r ->
                    r.slaveStream.buildSlaveRunId.equals(runTwoId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 0));

    // Process new logs

    SlaveStream runOneStdErrStream = new SlaveStream();
    runOneStdErrStream.setBuildSlaveRunId(runOneId);
    runOneStdErrStream.setStreamType(LogStreamType.STDERR);

    SlaveStream runOneStdOutStream = new SlaveStream();
    runOneStdOutStream.setBuildSlaveRunId(runOneId);
    runOneStdOutStream.setStreamType(LogStreamType.STDOUT);

    SlaveStream runTwoStdOutStream = new SlaveStream();
    runTwoStdOutStream.setBuildSlaveRunId(runTwoId);
    runTwoStdOutStream.setStreamType(LogStreamType.STDOUT);

    StreamLogs runOneStdErrLogs = new StreamLogs();
    runOneStdErrLogs.setSlaveStream(runOneStdErrStream);
    LogLineBatch runOneStdErrLogsBatchOne = new LogLineBatch();
    runOneStdErrLogsBatchOne.setBatchNumber(1);
    runOneStdErrLogsBatchOne.setLines(
        ImmutableList.of("runOneStdErrLine1\n", "runOneStdErrLine2\n"));
    runOneStdErrLogs.setLogLineBatches(ImmutableList.of(runOneStdErrLogsBatchOne));

    StreamLogs runTwoStdOutLogs = new StreamLogs();
    runTwoStdOutLogs.setSlaveStream(runTwoStdOutStream);
    LogLineBatch runTwoStdOutLogsBatchOne = new LogLineBatch();
    runTwoStdOutLogsBatchOne.setBatchNumber(1);
    runTwoStdOutLogsBatchOne.setLines(ImmutableList.of("runTwoStdOutLine1\n"));
    LogLineBatch runTwoStdOutLogsBatchTwo = new LogLineBatch();
    runTwoStdOutLogsBatchTwo.setBatchNumber(2);
    runTwoStdOutLogsBatchTwo.setLines(
        ImmutableList.of("runTwoStdOutLine2\n", "runTwoStdOutLine3\n"));
    runTwoStdOutLogs.setLogLineBatches(
        ImmutableList.of(runTwoStdOutLogsBatchOne, runTwoStdOutLogsBatchTwo));

    List<StreamLogs> streamLogsOne = ImmutableList.of(runOneStdErrLogs, runTwoStdOutLogs);

    distBuildLogStateTracker.processStreamLogs(streamLogsOne);

    assertLogLines(RUN_ONE_STD_ERR_LOG, ImmutableList.of("runOneStdErrLine1", "runOneStdErrLine2"));

    assertLogLines(
        RUN_TWO_STD_OUT_LOG,
        ImmutableList.of("runTwoStdOutLine1", "runTwoStdOutLine2", "runTwoStdOutLine3"));

    // Process new logs

    runTwoStdOutLogs = new StreamLogs();
    runTwoStdOutLogs.setSlaveStream(runTwoStdOutStream);

    runTwoStdOutLogsBatchTwo = new LogLineBatch();
    runTwoStdOutLogsBatchTwo.setBatchNumber(2);
    runTwoStdOutLogsBatchTwo.setLines(
        ImmutableList.of("runTwoStdOutLine2\n", "runTwoStdOutLine3\n", "runTwoStdOutLine4\n"));

    runTwoStdOutLogs.setLogLineBatches(ImmutableList.of(runTwoStdOutLogsBatchTwo));

    List<StreamLogs> streamLogsTwo = ImmutableList.of(runTwoStdOutLogs);

    distBuildLogStateTracker.processStreamLogs(streamLogsTwo);

    assertLogLines(
        RUN_TWO_STD_OUT_LOG,
        ImmutableList.of(
            "runTwoStdOutLine1", "runTwoStdOutLine2", "runTwoStdOutLine3", "runTwoStdOutLine4"));

    // runOne has stdErr, and runTwo has stdOut to download.
    buildSlaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);

    List<LogLineBatchRequest> requestTwo =
        distBuildLogStateTracker.createStreamLogRequests(buildSlaveInfos);

    assertThat(requestTwo.size(), Matchers.equalTo(4));

    // Request runOne/stdErr from batch 1
    assertTrue(
        requestTwo.stream()
            .anyMatch(
                r ->
                    r.slaveStream.buildSlaveRunId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDERR)
                        && r.batchNumber == 1));
    // Request runOne/stdOut from batch 1
    assertTrue(
        requestTwo.stream()
            .anyMatch(
                r ->
                    r.slaveStream.buildSlaveRunId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 0));
    // Request runTwo/stdOut from batch 2
    assertTrue(
        requestTwo.stream()
            .anyMatch(
                r ->
                    r.slaveStream.buildSlaveRunId.equals(runTwoId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 2));

    // Process new logs

    // runOne/stdErr
    runOneStdErrLogs = new StreamLogs();
    runOneStdErrLogs.setSlaveStream(runOneStdErrStream);
    runOneStdErrLogsBatchOne = new LogLineBatch();
    runOneStdErrLogsBatchOne.setBatchNumber(1);
    runOneStdErrLogsBatchOne.setLines(
        ImmutableList.of("runOneStdErrLine1\n", "runOneStdErrLine2\n", "runOneStdErrLine3\n"));
    LogLineBatch runOneStdErrLogsBatchTwo = new LogLineBatch();
    runOneStdErrLogsBatchTwo.setBatchNumber(2);
    runOneStdErrLogsBatchTwo.setLines(ImmutableList.of("runOneStdErrLine4\n"));
    runOneStdErrLogs.setLogLineBatches(
        ImmutableList.of(runOneStdErrLogsBatchOne, runOneStdErrLogsBatchTwo));

    // runOne/stdOut
    StreamLogs runOneStdOutLogs = new StreamLogs();
    runOneStdOutLogs.setSlaveStream(runOneStdOutStream);
    LogLineBatch runOneStdOutLogsBatchOne = new LogLineBatch();
    runOneStdOutLogsBatchOne.setBatchNumber(1);
    runOneStdOutLogsBatchOne.setLines(
        ImmutableList.of("runOneStdOutLine1\n", "runOneStdOutLine2\n"));
    LogLineBatch runOneStdOutLogsBatchTwo = new LogLineBatch();
    runOneStdOutLogsBatchTwo.setBatchNumber(2);
    runOneStdOutLogsBatchTwo.setLines(
        ImmutableList.of("runOneStdOutLine3\n", "runOneStdOutLine4\n"));
    runOneStdOutLogs.setLogLineBatches(
        ImmutableList.of(runOneStdOutLogsBatchOne, runOneStdOutLogsBatchTwo));

    // runTwo/stdOut
    runTwoStdOutLogs = new StreamLogs();
    runTwoStdOutLogs.setSlaveStream(runTwoStdOutStream);
    runTwoStdOutLogsBatchTwo = new LogLineBatch();
    runTwoStdOutLogsBatchTwo.setBatchNumber(2);
    runTwoStdOutLogsBatchTwo.setLines(
        ImmutableList.of("runTwoStdOutLine2\n", "runTwoStdOutLine3\n", "runTwoStdOutLine4\n"));
    LogLineBatch runTwoStdOutLogsBatchThree = new LogLineBatch();
    runTwoStdOutLogsBatchThree.setBatchNumber(3);
    runTwoStdOutLogsBatchThree.setLines(
        ImmutableList.of("runTwoStdOutLine5\n", "runTwoStdOutLine6\n"));
    runTwoStdOutLogs.setLogLineBatches(
        ImmutableList.of(runTwoStdOutLogsBatchTwo, runTwoStdOutLogsBatchThree));

    List<StreamLogs> streamLogsThree =
        ImmutableList.of(runOneStdErrLogs, runOneStdOutLogs, runTwoStdOutLogs);

    distBuildLogStateTracker.processStreamLogs(streamLogsThree);

    assertLogLines(
        RUN_ONE_STD_OUT_LOG,
        ImmutableList.of(
            "runOneStdOutLine1", "runOneStdOutLine2", "runOneStdOutLine3", "runOneStdOutLine4"));

    assertLogLines(
        RUN_ONE_STD_ERR_LOG,
        ImmutableList.of(
            "runOneStdErrLine1", "runOneStdErrLine2", "runOneStdErrLine3", "runOneStdErrLine4"));

    assertLogLines(
        RUN_TWO_STD_OUT_LOG,
        ImmutableList.of(
            "runTwoStdOutLine1",
            "runTwoStdOutLine2",
            "runTwoStdOutLine3",
            "runTwoStdOutLine4",
            "runTwoStdOutLine5",
            "runTwoStdOutLine6"));
  }

  private void assertLogLines(String filePath, List<String> logLines) throws IOException {
    assertTrue("Log file does not exist: " + filePath, logDir.resolve(filePath).toFile().exists());
    try (Stream<String> stream = Files.lines(logDir.resolve(filePath).toAbsolutePath())) {
      AtomicInteger lineIndex = new AtomicInteger(0);
      stream.forEachOrdered(
          line -> {
            assertThat(
                "Expected number of log lines lower than actual number.",
                lineIndex.get(),
                Matchers.lessThan(logLines.size()));
            assertThat(logLines.get(lineIndex.get()), Matchers.equalTo(line));
            lineIndex.getAndIncrement();
          });
      assertThat(
          "Expected number of log lines greater than actual number.",
          lineIndex.get(),
          Matchers.equalTo(logLines.size()));
    }
  }
}
