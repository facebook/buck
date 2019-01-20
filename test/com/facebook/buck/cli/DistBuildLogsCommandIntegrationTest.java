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

package com.facebook.buck.cli;

import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildStatusResponse;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogDir;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.FakeFrontendHttpServer;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/** Integration test for the distbuild logs command. */
public class DistBuildLogsCommandIntegrationTest {

  private static final StampedeId STAMPEDE_ID = new StampedeId().setId("super_cool_stampede_id");

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void runCommandAndCheckOutputFile() throws IOException {
    BuildSlaveInfo slave1 =
        new BuildSlaveInfo().setBuildSlaveRunId(new BuildSlaveRunId().setId("build_slave_1"));
    BuildSlaveInfo slave2 =
        new BuildSlaveInfo().setBuildSlaveRunId(new BuildSlaveRunId().setId("build_slave_2"));
    BuildJob buildJob = new BuildJob().setStampedeId(STAMPEDE_ID);
    buildJob.addToBuildSlaves(slave1);
    buildJob.addToBuildSlaves(slave2);

    LogDir logDir1 =
        new LogDir().setBuildSlaveRunId(slave1.getBuildSlaveRunId()).setData(getZipContents());
    LogDir logDir2 =
        new LogDir().setBuildSlaveRunId(slave2.getBuildSlaveRunId()).setData(getZipContents());
    List<LogDir> logDirs = Lists.newArrayList(logDir1, logDir2);

    try (Server server = new Server(buildJob, logDirs)) {
      ProjectWorkspace workspace =
          TestDataHelper.createProjectWorkspaceForScenario(this, "dist_build_logs_command", tmp);
      workspace.setUp();
      workspace
          .runBuckCommand(
              "distbuild",
              "logs",
              "--stampede-id=" + STAMPEDE_ID.getId(),
              server.getStampedeConfigArg(),
              server.getPingEndpointConfigArg())
          .assertSuccess();

      ListAllFiles filesLister = new ListAllFiles();
      Files.walkFileTree(tmp.getRoot(), filesLister);

      List<String> helloWorldFiles =
          filesLister
              .getAbsPaths()
              .stream()
              .filter(x -> x.contains("hello_world.txt"))
              .sorted()
              .collect(Collectors.toList());

      Assert.assertEquals(2, helloWorldFiles.size());
      Assert.assertTrue(helloWorldFiles.get(0).contains(slave1.getBuildSlaveRunId().getId()));
      Assert.assertTrue(helloWorldFiles.get(1).contains(slave2.getBuildSlaveRunId().getId()));
    }
  }

  private byte[] getZipContents() {
    // Zip file containing one empty file named "hello_world.txt".
    String base64Contents =
        "UEsDBAoAAAAAADNUU0sAAAAAAAAAAAAAAAAPABwAaGVsbG9fd29ybGQudHh0VVQJAA"
            + "NycehZcnHoWXV4CwABBNE7wRgEui3Tb1BLAQIeAwoAAAAAADNUU0sAAAAAAAAAAAAAAAAPABgAAAAAAAAAAACkg"
            + "QAAAABoZWxsb193b3JsZC50eHRVVAUAA3Jx6Fl1eAsAAQTRO8EYBLot029QSwUGAAAAAAEAAQBVAAAASQAAAAAA";
    return Base64.getDecoder().decode(base64Contents);
  }

  private static class ListAllFiles implements FileVisitor<Path> {

    List<String> absPaths;

    public ListAllFiles() {
      absPaths = Lists.newArrayList();
    }

    public List<String> getAbsPaths() {
      return absPaths;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      absPaths.add(file.toAbsolutePath().toString());
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      return FileVisitResult.CONTINUE;
    }
  }

  private static class Server extends FakeFrontendHttpServer {

    private final BuildJob buildJob;
    private final List<LogDir> logDirs;

    public Server(BuildJob buildJob, List<LogDir> logDirs) throws IOException {
      this.buildJob = buildJob;
      this.logDirs = logDirs;
    }

    @Override
    public FrontendResponse handleRequest(FrontendRequest request) {
      FrontendResponse response = new FrontendResponse();
      response.setType(request.getType());
      response.setWasSuccessful(true);

      if (request.getType() == FrontendRequestType.BUILD_STATUS) {
        BuildStatusResponse statusResponse = new BuildStatusResponse();
        statusResponse.setBuildJob(buildJob);
        response.setBuildStatusResponse(statusResponse);
      } else if (request.getType() == FrontendRequestType.GET_BUILD_SLAVE_LOG_DIR) {
        MultiGetBuildSlaveLogDirResponse logDirResponse = new MultiGetBuildSlaveLogDirResponse();
        logDirResponse.setLogDirs(logDirs);
        response.setMultiGetBuildSlaveLogDirResponse(logDirResponse);
      } else {
        Assert.fail("This call was not expected.");
      }

      return response;
    }
  }
}
