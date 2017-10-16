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

import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.SetFinalBuildStatusResponse;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private void runSimpleDistBuildScenario(String scenario, String targetToBuild)
      throws IOException {
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");

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

    runDistBuildWithFakeFrontend(
            destinationWorkspace, "--build-state-file", stateFilePath.toString())
        .assertSuccess();
  }

  @Test
  public void canBuildJavaCode() throws Exception {
    runSimpleDistBuildScenario("simple_java_target", "//:lib1");
  }

  @Test
  public void canBuildCrossCellWithSymlinksAndAbsPathTools() throws Exception {
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");
    final String scenario = "multi_cell_java_target";
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

    runDistBuildWithFakeFrontend(
            destinationWorkspace, "--build-state-file", stateFilePath.toString())
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
        TestDataHelper.createProjectWorkspaceForScenario(
            this, scenario + "/" + cellSubDir, cellPath);
    sourceWorkspace.setUp();
    return sourceWorkspace;
  }

  private static ProjectWorkspace.ProcessResult runDistBuildWithFakeFrontend(
      ProjectWorkspace workspace, String... args) throws IOException {
    List<String> argsList = Lists.newArrayList(args);
    try (FakeFrontendHttpServer frontendServer = new FakeFrontendHttpServer()) {
      argsList.add(frontendServer.getStampedeConfigArg());
      argsList.add(frontendServer.getPingEndpointConfigArg());
      return workspace.runBuckDistBuildRun(argsList.toArray(new String[0]));
    }
  }

  /**
   * Fake Stampede.Frontend server that provides enough of an HTTP API surface to allow us to run
   * integration tests in the local machine without have to connect to any real remote
   * Stampede.Frontend servers.
   */
  private static class FakeFrontendHttpServer implements Closeable {
    private static final int MAX_CONNECTIONS_WAITING_IN_QUEUE = 42;

    private final int port;
    private final Thread serverThread;
    private final HttpServer server;

    public FakeFrontendHttpServer() throws IOException {
      this.port = ThriftCoordinatorServerIntegrationTest.findRandomOpenPortOnAllLocalInterfaces();
      this.server =
          HttpServer.create(new InetSocketAddress(port), MAX_CONNECTIONS_WAITING_IN_QUEUE);
      this.server.createContext("/status.php", httpExchange -> handleStatusRequest(httpExchange));
      this.server.createContext("/thrift", httpExchange -> handleThriftRequest(httpExchange));
      this.serverThread =
          new Thread(
              () -> {
                server.start();
              });
      this.serverThread.start();
    }

    private void handleStatusRequest(HttpExchange httpExchange) throws IOException {
      byte[] iAmAlive = "I am alive and happy!!!".getBytes();
      httpExchange.sendResponseHeaders(200, iAmAlive.length);
      try (OutputStream os = httpExchange.getResponseBody()) {
        os.write(iAmAlive);
      }
    }

    private void handleThriftRequest(HttpExchange httpExchange) throws IOException {
      FrontendResponse response =
          new FrontendResponse()
              .setType(FrontendRequestType.SET_FINAL_BUILD_STATUS)
              .setWasSuccessful(true)
              .setSetFinalBuildStatusResponse(new SetFinalBuildStatusResponse());

      byte[] requestBytes = ByteStreams.toByteArray(httpExchange.getRequestBody());
      FrontendRequest request = new FrontendRequest();
      ThriftUtil.deserialize(ThriftProtocol.BINARY, requestBytes, request);
      org.junit.Assert.assertEquals(FrontendRequestType.SET_FINAL_BUILD_STATUS, request.getType());

      byte[] responseBuffer = ThriftUtil.serialize(ThriftProtocol.BINARY, response);
      httpExchange.sendResponseHeaders(200, responseBuffer.length);
      try (OutputStream os = httpExchange.getResponseBody()) {
        os.write(responseBuffer);
      }
    }

    @Override
    public void close() throws IOException {
      this.server.stop(0);
      try {
        this.serverThread.join();
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    }

    public String getPingEndpointConfigArg() {
      return "--config=stampede.slb_ping_endpoint=/status.php";
    }

    public String getStampedeConfigArg() {
      return String.format("--config=stampede.slb_server_pool=http://localhost:%s", port);
    }
  }
}
