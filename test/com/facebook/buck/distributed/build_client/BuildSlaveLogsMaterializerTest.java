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

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.LogDir;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuildSlaveLogsMaterializerTest {

  private static final StampedeId STAMPEDE_ID = new StampedeId().setId("down the line");
  private static final BuildSlaveRunId RUN_ONE_ID =
      new BuildSlaveRunId().setId("buildSlaveRunIdOne");
  private static final BuildSlaveRunId RUN_TWO_ID =
      new BuildSlaveRunId().setId("buildSlaveRunIdTwo");

  private static final String LOG_DIR = "logs";

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

  private static final String RUN_ONE_BUCK_OUT_DIR =
      "dist-build-slave-buildSlaveRunIdOne/buck-out-log";
  private static final String RUN_TWO_BUCK_OUT_DIR =
      "dist-build-slave-buildSlaveRunIdTwo/buck-out-log";

  @Rule public TemporaryFolder projectDir = new TemporaryFolder();

  private Path logDir;
  private ProjectFilesystem projectFilesystem;
  private DistBuildService mockService;

  @Before
  public void setUp() throws InterruptedException {
    projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(projectDir.getRoot().toPath());
    mockService = EasyMock.createMock(DistBuildService.class);
    logDir = projectDir.getRoot().toPath().resolve(LOG_DIR);
  }

  @Test
  public void testFetchingLogsFromDistBuildService() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    DistBuildService service = EasyMock.createMock(DistBuildService.class);
    Path logsPath = filesystem.getRootPath();

    LogDir logDir0 = new LogDir();
    logDir0.setBuildSlaveRunId(new BuildSlaveRunId().setId("one"));
    logDir0.setData("Here is some data.".getBytes());
    LogDir logDir1 = new LogDir();
    logDir1.setBuildSlaveRunId(new BuildSlaveRunId().setId("two"));
    logDir1.setData("Here is some more data.".getBytes());

    MultiGetBuildSlaveLogDirResponse logDirResponse = new MultiGetBuildSlaveLogDirResponse();
    logDirResponse.addToLogDirs(logDir0);
    logDirResponse.addToLogDirs(logDir1);
    StampedeId stampedeId = new StampedeId().setId("down the line");
    List<BuildSlaveRunId> buildSlaveRunIdsToFetch = Lists.newArrayList();
    EasyMock.expect(
            service.fetchBuildSlaveLogDir(
                EasyMock.eq(stampedeId), EasyMock.eq(buildSlaveRunIdsToFetch)))
        .andReturn(logDirResponse)
        .once();
    EasyMock.replay(service);

    BuildSlaveLogsMaterializer materializer =
        new BuildSlaveLogsMaterializer(service, filesystem, logsPath);

    buildSlaveRunIdsToFetch.add(new BuildSlaveRunId().setId("topspin"));
    List<LogDir> result = materializer.fetchBuildSlaveLogDirs(stampedeId, buildSlaveRunIdsToFetch);
    Assert.assertEquals(2, result.size());

    EasyMock.verify(service);
  }

  @Test
  public void testMaterializesBuckOutDirForRuns() throws IOException {
    LogDir logDirOne = new LogDir();
    logDirOne.setBuildSlaveRunId(RUN_ONE_ID);
    logDirOne.setData(readTestData(RUN_ONE_BUCK_OUT_ZIP));

    LogDir logDirTwo = new LogDir();
    logDirTwo.setBuildSlaveRunId(RUN_TWO_ID);
    logDirTwo.setData(readTestData(RUN_TWO_BUCK_OUT_ZIP));

    List<LogDir> logDirs = ImmutableList.of(logDirOne, logDirTwo);
    BuildSlaveLogsMaterializer materializer =
        new BuildSlaveLogsMaterializer(mockService, projectFilesystem, logDir);
    materializer.materializeLogDirs(logDirs);

    Path runOneBuckOutDir = logDir.resolve(RUN_ONE_BUCK_OUT_DIR);
    Assert.assertTrue(runOneBuckOutDir.toFile().exists());
    Assert.assertTrue(runOneBuckOutDir.resolve(FILE_ONE_PATH).toFile().exists());
    Assert.assertTrue(runOneBuckOutDir.resolve(FILE_TWO_PATH).toFile().exists());

    String fileOneContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_ONE_PATH)));
    Assert.assertThat(fileOneContents, Matchers.equalTo(FILE_ONE_CONTENTS));

    String fileTwoContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_TWO_PATH)));
    Assert.assertThat(fileTwoContents, Matchers.equalTo(FILE_TWO_CONTENTS));

    Path runTwoBuckOutDir = logDir.resolve(RUN_TWO_BUCK_OUT_DIR);
    Assert.assertTrue(runTwoBuckOutDir.toFile().exists());
    Assert.assertTrue(runTwoBuckOutDir.resolve(FILE_THREE_PATH).toFile().exists());
    Assert.assertTrue(runTwoBuckOutDir.resolve(FILE_FOUR_PATH).toFile().exists());

    String fileThreeContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_THREE_PATH)));
    Assert.assertThat(fileThreeContents, Matchers.equalTo(FILE_THREE_CONTENTS));

    String fileFourContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_FOUR_PATH)));
    Assert.assertThat(fileFourContents, Matchers.equalTo(FILE_FOUR_CONTENTS));
  }

  @Test(expected = TimeoutException.class)
  public void testFetchWithTimeoutFailsIfLogsNeverBecomeAvailable()
      throws IOException, TimeoutException {
    LogDir emptyLogDir = new LogDir();
    emptyLogDir.setBuildSlaveRunId(RUN_TWO_ID);
    emptyLogDir.setErrorMessage("things did not go well....");
    List<LogDir> logDirs = ImmutableList.of(emptyLogDir);
    MultiGetBuildSlaveLogDirResponse response = new MultiGetBuildSlaveLogDirResponse();
    response.setLogDirs(logDirs);

    List<BuildSlaveRunId> runIdsToFetch = Lists.newArrayList(RUN_TWO_ID);
    EasyMock.expect(mockService.fetchBuildSlaveLogDir(STAMPEDE_ID, runIdsToFetch))
        .andReturn(response)
        .atLeastOnce();
    EasyMock.replay(mockService);

    BuildSlaveLogsMaterializer materializer =
        new BuildSlaveLogsMaterializer(mockService, projectFilesystem, logDir);
    try {
      materializer.fetchAndMaterializeAllLogs(STAMPEDE_ID, runIdsToFetch, 100);
    } finally {
      EasyMock.verify(mockService);
    }
  }

  @Test
  public void testFetchWithTimeoutWorksIfLogsArePresent() throws IOException, TimeoutException {
    LogDir logDirWitData = new LogDir();
    logDirWitData.setBuildSlaveRunId(RUN_TWO_ID);
    logDirWitData.setData(readTestData(RUN_TWO_BUCK_OUT_ZIP));
    List<LogDir> logDirs = ImmutableList.of(logDirWitData);
    MultiGetBuildSlaveLogDirResponse response = new MultiGetBuildSlaveLogDirResponse();
    response.setLogDirs(logDirs);

    List<BuildSlaveRunId> runIdsToFetch = Lists.newArrayList(RUN_TWO_ID);
    EasyMock.expect(mockService.fetchBuildSlaveLogDir(STAMPEDE_ID, runIdsToFetch))
        .andReturn(response)
        .atLeastOnce();
    EasyMock.replay(mockService);

    BuildSlaveLogsMaterializer materializer =
        new BuildSlaveLogsMaterializer(mockService, projectFilesystem, logDir);
    materializer.fetchAndMaterializeAllLogs(STAMPEDE_ID, runIdsToFetch, 100);
    EasyMock.verify(mockService);
    Assert.assertTrue(
        logDir.resolve(RUN_TWO_BUCK_OUT_DIR).resolve(FILE_THREE_PATH).toFile().exists());
  }

  private byte[] readTestData(String path) throws IOException {
    return Files.readAllBytes(TestDataHelper.getTestDataDirectory(getClass()).resolve(path));
  }
}
