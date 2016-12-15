/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.event.listener.integration;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.event.listener.DistBuildLoggerListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DistBuildLoggerListenerTest {
  @Rule
  public TemporaryFolder projectDir = new TemporaryFolder();

  private static final String LOG_DIR = "logs";

  // run_one_buck_out.zip contains the following files:
  // file1: abc
  // file2: def
  private static final String RUN_ONE_BUCK_OUT_ZIP = "build_slave_logs/run_one_buck_out.zip";

  // run_two_buck_out.zip contains the following files:
  // file3: ghi
  // file4: jkl
  private static final String RUN_TWO_BUCK_OUT_ZIP = "build_slave_logs/run_two_buck_out.zip";

  private static final String RUN_ONE_ID = "runIdOne";
  private static final String RUN_TWO_ID = "runIdTwo";

  private static final String FILE_ONE_PATH = "file1";
  private static final String FILE_TWO_PATH = "file2";
  private static final String FILE_ONE_CONTENTS = "abc\n";
  private static final String FILE_TWO_CONTENTS = "def\n";

  private static final String FILE_THREE_PATH = "file3";
  private static final String FILE_FOUR_PATH = "file4";
  private static final String FILE_THREE_CONTENTS = "ghi\n";
  private static final String FILE_FOUR_CONTENTS = "jkl\n";

  private static final String RUN_ONE_BUCK_OUT_DIR = "dist-build-slave-runIdOne/buck-out";
  private static final String RUN_TWO_BUCK_OUT_DIR = "dist-build-slave-runIdTwo/buck-out";

  @Test
  public void testMaterializesBuckOutDirForRuns() throws IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());

    Path logDir = projectDir.getRoot().toPath().resolve(LOG_DIR);

    DistBuildLoggerListener distBuildLoggerListener = new DistBuildLoggerListener(
        logDir,
        projectFilesystem,
        MoreExecutors.newDirectExecutorService());

    BuildSlaveInfo runOneSlaveInfo = new BuildSlaveInfo();
    RunId runOneId = new RunId();
    runOneId.setId(RUN_ONE_ID);
    runOneSlaveInfo.setRunId(runOneId);
    runOneSlaveInfo.setLogDirZipContents(Files.readAllBytes(TestDataHelper.getTestDataDirectory(
        getClass()).resolve(RUN_ONE_BUCK_OUT_ZIP)));

    BuildSlaveInfo runTwoSlaveInfo = new BuildSlaveInfo();
    RunId runTwoId = new RunId();
    runTwoId.setId(RUN_TWO_ID);
    runTwoSlaveInfo.setRunId(runTwoId);
    runTwoSlaveInfo.setLogDirZipContents(Files.readAllBytes(TestDataHelper.getTestDataDirectory(
        getClass()).resolve(RUN_TWO_BUCK_OUT_ZIP)));

    DistBuildStatus distBuildStatus = DistBuildStatus
        .builder()
        .setETAMillis(0)
        .setStatus(BuildStatus.FINISHED_SUCCESSFULLY)
        .setSlaveInfoByRunId(ImmutableMap.of(
            RUN_ONE_ID, runOneSlaveInfo,
            RUN_TWO_ID, runTwoSlaveInfo))
        .build();

    DistBuildStatusEvent distBuildEvent = new DistBuildStatusEvent(distBuildStatus);
    distBuildLoggerListener.distributedBuildStatus(distBuildEvent);
    distBuildLoggerListener.close();

    Path runOneBuckOutDir = logDir.resolve(RUN_ONE_BUCK_OUT_DIR);
    assertTrue(runOneBuckOutDir.toFile().exists());
    assertTrue(runOneBuckOutDir.resolve(FILE_ONE_PATH).toFile().exists());
    assertTrue(runOneBuckOutDir.resolve(FILE_TWO_PATH).toFile().exists());

    String fileOneContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_ONE_PATH)));
    assertThat(
        fileOneContents,
        Matchers.equalTo(FILE_ONE_CONTENTS));

    String fileTwoContents =
        new String(Files.readAllBytes(runOneBuckOutDir.resolve(FILE_TWO_PATH)));
    assertThat(
        fileTwoContents,
        Matchers.equalTo(FILE_TWO_CONTENTS));

    Path runTwoBuckOutDir = logDir.resolve(RUN_TWO_BUCK_OUT_DIR);
    assertTrue(runTwoBuckOutDir.toFile().exists());
    assertTrue(runTwoBuckOutDir.resolve(FILE_THREE_PATH).toFile().exists());
    assertTrue(runTwoBuckOutDir.resolve(FILE_FOUR_PATH).toFile().exists());

    String fileThreeContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_THREE_PATH)));
    assertThat(
        fileThreeContents,
        Matchers.equalTo(FILE_THREE_CONTENTS));

    String fileFourContents =
        new String(Files.readAllBytes(runTwoBuckOutDir.resolve(FILE_FOUR_PATH)));
    assertThat(
        fileFourContents,
        Matchers.equalTo(FILE_FOUR_CONTENTS));
  }
}
