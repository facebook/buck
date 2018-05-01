/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildStatusResponse;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.ReportCoordinatorAliveResponse;
import com.facebook.buck.distributed.thrift.SetFinalBuildStatusResponse;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.FakeFrontendHttpServer;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private void runSimpleDistBuildScenario(String scenario, String targetToBuild)
      throws IOException {
    Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");
    Path destinationFolderPath = temporaryFolder.newFolder("destination");

    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, sourceFolderPath);
    sourceWorkspace.setUp();

    sourceWorkspace
        .runBuckBuild(
            targetToBuild, "--distributed", "--build-state-file", stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    FrontendServer.runDistBuildWithFakeFrontend(
            destinationWorkspace,
            "--build-state-file",
            stateFilePath.toString(),
            "--buildslave-run-id",
            "sl1")
        .assertSuccess();
  }

  @Test
  public void canBuildJavaCode() throws Exception {
    runSimpleDistBuildScenario("simple_java_target", "//:lib1");
  }

  @Test
  public void canBuildJumpingIntoSecondaryCellAndBackToMainCell() throws Exception {
    testCrossCell("multi_cell_out_and_back");
  }

  @Test
  public void canBuildCrossCellWithGenRules() throws Exception {
    testCrossCell("multi_cell_genrule_target");
  }

  private void testCrossCell(String scenario) throws Exception {
    Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path destinationFolderPath = temporaryFolder.newFolder("destination");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");

    ProjectWorkspace mainCellWorkspace = setupCell(scenario, "main_cell", sourceFolderPath);
    setupCell(scenario, "secondary_cell", sourceFolderPath);

    mainCellWorkspace
        .runBuckBuild(
            "//:cross_cell_gen_rule",
            "--distributed",
            "--build-state-file",
            stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    FrontendServer.runDistBuildWithFakeFrontend(
            destinationWorkspace,
            "--build-state-file",
            stateFilePath.toString(),
            "--buildslave-run-id",
            "i_am_slave_with_run_id_42")
        .assertSuccess();
  }

  @Test
  public void canBuildCrossCellWithSymlinksAndAbsPathTools() throws Exception {
    Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path destinationFolderPath = temporaryFolder.newFolder("destination");
    String scenario = "multi_cell_java_target";
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");

    ProjectWorkspace mainCellWorkspace = setupCell(scenario, "main_cell", sourceFolderPath);
    setupCell(scenario, "secondary_cell", sourceFolderPath);
    ProjectWorkspace absPathWorkspace = setupCell(scenario, "abs_path_dir", sourceFolderPath);
    Cell mainCell = mainCellWorkspace.asCell();

    Path dJavaFileAbsPath = absPathWorkspace.asCell().getFilesystem().resolve("D.java");
    Path dJavaFileSymlinkAbsPath = mainCell.getFilesystem().resolve("D.java");
    mainCell.getFilesystem().createSymLink(dJavaFileSymlinkAbsPath, dJavaFileAbsPath, false);

    mainCellWorkspace
        .runBuckBuild("//:libA", "--distributed", "--build-state-file", stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    FrontendServer.runDistBuildWithFakeFrontend(
            destinationWorkspace,
            "--build-state-file",
            stateFilePath.toString(),
            "--buildslave-run-id",
            "sl1")
        .assertSuccess();
  }

  @Test
  public void coercerDoesNotCheckFileExistence() throws Exception {
    // To be able to check this while we 'preload' all recorded dependencies (touch the source
    // files), we create a scenario with a version dependency that gets shaved off in the
    // VersionedTargetGraph. So this dependency doesn't get recorded for pre-loading, and the
    // coercer must skip existence check for this file.

    // Explanation of test scenario: Both lib1 and lib2 exist as dependencies in the
    // UnversionedTargetGraph. But lib1 is shaved off before we record dependencies from the
    // VersionedTargetGraph. When we generate the UnversionedTargetGraph again on the slave, the
    // default PathTypeCoercer checks for the existence of files for lib1, which do not exist,
    // hence failing the test (unless we use the DO_NOT_VERIFY mode).
    runSimpleDistBuildScenario("versioned_target", "//:bin");
  }

  @Test
  public void preloadingMaterializesWhitelist() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    runSimpleDistBuildScenario("preloading_whitelist", "//:libA");
  }

  private ProjectWorkspace setupCell(String scenario, String cellSubDir, Path outputDir)
      throws IOException {
    Path cellPath = outputDir.resolve(cellSubDir);
    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, scenario + "/" + cellSubDir, cellPath);
    sourceWorkspace.setUp();
    return sourceWorkspace;
  }

  public static class FrontendServer extends FakeFrontendHttpServer {

    public static ProcessResult runDistBuildWithFakeFrontend(
        ProjectWorkspace workspace, String... args) throws IOException {
      List<String> argsList = Lists.newArrayList(args);
      try (FrontendServer frontendServer = new FrontendServer()) {
        argsList.add(frontendServer.getStampedeConfigArg());
        argsList.add(frontendServer.getPingEndpointConfigArg());
        return workspace.runBuckDistBuildRun(argsList.toArray(new String[0]));
      }
    }

    private FrontendServer() throws IOException {}

    @Override
    public FrontendResponse handleRequest(FrontendRequest request) {
      switch (request.getType()) {
        case REPORT_COORDINATOR_ALIVE:
          return new FrontendResponse()
              .setType(FrontendRequestType.REPORT_COORDINATOR_ALIVE)
              .setWasSuccessful(true)
              .setReportCoordinatorAliveResponse(new ReportCoordinatorAliveResponse());

        case SET_FINAL_BUILD_STATUS:
          return new FrontendResponse()
              .setType(FrontendRequestType.SET_FINAL_BUILD_STATUS)
              .setWasSuccessful(true)
              .setSetFinalBuildStatusResponse(new SetFinalBuildStatusResponse());

        case BUILD_STATUS:
          return new FrontendResponse()
              .setType(FrontendRequestType.BUILD_STATUS)
              .setWasSuccessful(true)
              .setBuildStatusResponse(
                  new BuildStatusResponse()
                      .setBuildJob(
                          new BuildJob()
                              .setBuckBuildUuid("11-22")
                              .setStampedeId(request.getBuildStatusRequest().getStampedeId())));

          // $CASES-OMITTED$
        default:
          Assert.fail("Unexpected request type: " + request.getType());
          // Never gets here.
          return new FrontendResponse();
      }
    }
  }
}
