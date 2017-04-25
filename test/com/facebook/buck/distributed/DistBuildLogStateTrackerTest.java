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
package com.facebook.buck.distributed;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.LogDir;
import com.facebook.buck.distributed.thrift.LogLineBatch;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.LogStreamType;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.SlaveStream;
import com.facebook.buck.distributed.thrift.StreamLogs;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DistBuildLogStateTrackerTest {
  @Rule public TemporaryFolder projectDir = new TemporaryFolder();

  private static final String LOG_DIR = "logs";

  private static final String RUN_ONE_BUCK_OUT_DIR = "dist-build-slave-runIdOne/buck-out";
  private static final String RUN_TWO_BUCK_OUT_DIR = "dist-build-slave-runIdTwo/buck-out";

  private static final String RUN_ONE_ID = "runIdOne";
  private static final String RUN_TWO_ID = "runIdTwo";

  /*
   **********************************
   * File materialization test data
   **********************************
   */

  // run_one_buck_out.zip contains the following files:
  // file1: abc
  // file2: def
  private static final String RUN_ONE_BUCK_OUT_ZIP = "build_slave_logs/run_one_buck_out.zip";

  // run_two_buck_out.zip contains the following files:
  // file3: ghi
  // file4: jkl
  private static final String RUN_TWO_BUCK_OUT_ZIP = "build_slave_logs/run_two_buck_out.zip";

  // Files contained inside RUN_ONE_BUCK_OUT_ZIP
  private static final String FILE_ONE_PATH = "file1";
  private static final String FILE_TWO_PATH = "file2";
  private static final String FILE_ONE_CONTENTS = "abc\n";
  private static final String FILE_TWO_CONTENTS = "def\n";

  // Files contained inside RUN_TWO_BUCK_OUT_ZIP
  private static final String FILE_THREE_PATH = "file3";
  private static final String FILE_FOUR_PATH = "file4";
  private static final String FILE_THREE_CONTENTS = "ghi\n";
  private static final String FILE_FOUR_CONTENTS = "jkl\n";

  /*
   **********************************
   * Log streaming test data
   **********************************
   */

  private static final String RUN_ONE_STD_ERR_LOG = "dist-build-slave-runIdOne/STDERR.log";
  private static final String RUN_ONE_STD_OUT_LOG = "dist-build-slave-runIdOne/STDOUT.log";
  private static final String RUN_TWO_STD_OUT_LOG = "dist-build-slave-runIdTwo/STDOUT.log";

  private Path logDir;
  private DistBuildLogStateTracker distBuildLogStateTracker;

  /*
   **********************************
   * Tests
   **********************************
   */

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    logDir = projectDir.getRoot().toPath().resolve(LOG_DIR);
    distBuildLogStateTracker = new DistBuildLogStateTracker(logDir, projectFilesystem);
  }

  @Test
  public void testStreamsLogsForRuns() throws IOException {
    BuildSlaveInfo runOneSlaveInfo = new BuildSlaveInfo();
    RunId runOneId = new RunId();
    runOneId.setId(RUN_ONE_ID);
    runOneSlaveInfo.setRunId(runOneId);
    runOneSlaveInfo.setStdOutCurrentBatchNumber(0);
    runOneSlaveInfo.setStdOutCurrentBatchLineCount(0);
    runOneSlaveInfo.setStdErrCurrentBatchNumber(1);
    runOneSlaveInfo.setStdErrCurrentBatchLineCount(1);

    BuildSlaveInfo runTwoSlaveInfo = new BuildSlaveInfo();
    RunId runTwoId = new RunId();
    runTwoId.setId(RUN_TWO_ID);
    runTwoSlaveInfo.setRunId(runTwoId);
    runTwoSlaveInfo.setStdOutCurrentBatchNumber(2);
    runTwoSlaveInfo.setStdOutCurrentBatchLineCount(2);
    runTwoSlaveInfo.setStdErrCurrentBatchNumber(0);
    runTwoSlaveInfo.setStdErrCurrentBatchLineCount(0);

    // runOne has stdErr, and runTwo has stdOut to download.
    List<BuildSlaveInfo> buildSlaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);
    List<LogLineBatchRequest> requestsOne =
        distBuildLogStateTracker.createRealtimeLogRequests(buildSlaveInfos);

    assertThat(requestsOne.size(), Matchers.equalTo(2));

    // Request runOne/stdErr from batch 1
    assertTrue(
        requestsOne
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDERR)
                        && r.batchNumber == 1));
    // Request runTwo/stdOut from batch 1
    assertTrue(
        requestsOne
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runTwoId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 1));

    // Process new logs

    SlaveStream runOneStdErrStream = new SlaveStream();
    runOneStdErrStream.setRunId(runOneId);
    runOneStdErrStream.setStreamType(LogStreamType.STDERR);

    SlaveStream runOneStdOutStream = new SlaveStream();
    runOneStdOutStream.setRunId(runOneId);
    runOneStdOutStream.setStreamType(LogStreamType.STDOUT);

    SlaveStream runTwoStdOutStream = new SlaveStream();
    runTwoStdOutStream.setRunId(runTwoId);
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

    // New build status arrives.
    // runOne/stdErr has same values as last time (so fewer lines than already processed).
    // => ignore
    // runTwo/stdOut updated within existing batch 2
    // => fetch batch 2 and process new lines

    runTwoSlaveInfo.setStdOutCurrentBatchNumber(2);
    runTwoSlaveInfo.setStdOutCurrentBatchLineCount(3);
    buildSlaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);

    List<LogLineBatchRequest> requestsTwo =
        distBuildLogStateTracker.createRealtimeLogRequests(buildSlaveInfos);

    assertThat(requestsTwo.size(), Matchers.equalTo(1));
    // Request runTwo/stdOut from batch 2
    assertTrue(
        requestsTwo
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runTwoId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 2));

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

    // New build status arrives.
    // runOne/stdOut has now been populated with 2 batches
    // runOne/stdErr updated to new batch 2, with changes to batch 1 too
    // => fetch 1 and 2, processing new lines in batch 1 and all lines in batch 2.
    // runTwo/stdOut updated to new batch 3, no changes to existing batches
    // => fetch batch 2 and 3, processing only changes in batch 3

    runOneSlaveInfo.setStdOutCurrentBatchNumber(2);
    runOneSlaveInfo.setStdOutCurrentBatchLineCount(2);
    runOneSlaveInfo.setStdErrCurrentBatchNumber(2);
    runOneSlaveInfo.setStdErrCurrentBatchLineCount(1);

    runTwoSlaveInfo.setStdOutCurrentBatchNumber(3);
    runTwoSlaveInfo.setStdOutCurrentBatchLineCount(2);

    // runOne has stdErr, and runTwo has stdOut to download.
    buildSlaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);

    List<LogLineBatchRequest> requestsThree =
        distBuildLogStateTracker.createRealtimeLogRequests(buildSlaveInfos);

    assertThat(requestsThree.size(), Matchers.equalTo(3));

    // Request runOne/stdErr from batch 1
    assertTrue(
        requestsThree
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDERR)
                        && r.batchNumber == 1));
    // Request runOne/stdOut from batch 1
    assertTrue(
        requestsThree
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runOneId)
                        && r.slaveStream.streamType.equals(LogStreamType.STDOUT)
                        && r.batchNumber == 1));
    // Request runTwo/stdOut from batch 2
    assertTrue(
        requestsThree
            .stream()
            .anyMatch(
                r ->
                    r.slaveStream.runId.equals(runTwoId)
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

  private void assertLogLines(String filePath, final List<String> logLines) throws IOException {
    assertTrue("Log file does not exist: " + filePath, logDir.resolve(filePath).toFile().exists());
    try (Stream<String> stream = Files.lines(logDir.resolve(filePath).toAbsolutePath())) {
      final AtomicInteger lineIndex = new AtomicInteger(0);
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

  @Test
  public void testMaterializesBuckOutDirForRuns() throws IOException {
    BuildSlaveInfo runOneSlaveInfo = new BuildSlaveInfo();
    RunId runOneId = new RunId();
    runOneId.setId(RUN_ONE_ID);
    runOneSlaveInfo.setRunId(runOneId);
    runOneSlaveInfo.setLogDirZipWritten(true);

    BuildSlaveInfo runTwoSlaveInfo = new BuildSlaveInfo();
    RunId runTwoId = new RunId();
    runTwoId.setId(RUN_TWO_ID);
    runTwoSlaveInfo.setRunId(runTwoId);
    runTwoSlaveInfo.setLogDirZipWritten(true);

    List<BuildSlaveInfo> slaveInfos = ImmutableList.of(runOneSlaveInfo, runTwoSlaveInfo);
    List<RunId> runIdsToMaterialize =
        distBuildLogStateTracker.runIdsToMaterializeLogDirsFor(slaveInfos);
    assertThat(runIdsToMaterialize.size(), Matchers.equalTo(2));
    assertThat(runIdsToMaterialize, Matchers.contains(runOneId, runTwoId));

    LogDir logDirOne = new LogDir();
    logDirOne.setRunId(runOneId);
    logDirOne.setData(readTestData(RUN_ONE_BUCK_OUT_ZIP));

    LogDir logDirTwo = new LogDir();
    logDirTwo.setRunId(runTwoId);
    logDirTwo.setData(readTestData(RUN_TWO_BUCK_OUT_ZIP));

    List<LogDir> logDirs = ImmutableList.of(logDirOne, logDirTwo);
    distBuildLogStateTracker.materializeLogDirs(logDirs);

    Path runOneBuckOutDir = logDir.resolve(RUN_ONE_BUCK_OUT_DIR);
    assertTrue(runOneBuckOutDir.toFile().exists());
    assertTrue(runOneBuckOutDir.resolve(FILE_ONE_PATH).toFile().exists());
    assertTrue(runOneBuckOutDir.resolve(FILE_TWO_PATH).toFile().exists());

    String fileOneContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_ONE_PATH)));
    assertThat(fileOneContents, Matchers.equalTo(FILE_ONE_CONTENTS));

    String fileTwoContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_TWO_PATH)));
    assertThat(fileTwoContents, Matchers.equalTo(FILE_TWO_CONTENTS));

    Path runTwoBuckOutDir = logDir.resolve(RUN_TWO_BUCK_OUT_DIR);
    assertTrue(runTwoBuckOutDir.toFile().exists());
    assertTrue(runTwoBuckOutDir.resolve(FILE_THREE_PATH).toFile().exists());
    assertTrue(runTwoBuckOutDir.resolve(FILE_FOUR_PATH).toFile().exists());

    String fileThreeContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_THREE_PATH)));
    assertThat(fileThreeContents, Matchers.equalTo(FILE_THREE_CONTENTS));

    String fileFourContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_FOUR_PATH)));
    assertThat(fileFourContents, Matchers.equalTo(FILE_FOUR_CONTENTS));
  }

  private byte[] readTestData(String path) throws IOException {
    return Files.readAllBytes(TestDataHelper.getTestDataDirectory(getClass()).resolve(path));
  }
}
