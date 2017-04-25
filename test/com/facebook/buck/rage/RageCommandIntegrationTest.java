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

package com.facebook.buck.rage;

import static com.facebook.buck.rage.AbstractRageConfig.RageProtocolVersion;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RageCommandIntegrationTest {

  private static final String UPLOAD_PATH = "/rage";

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private static final String BUILD_COMMAND_DIR_PATH =
      "buck-out/log/" + "2016-06-21_16h16m24s_buildcommand_ac8bd626-6137-4747-84dd-5d4f215c876c/";
  private static final String AUTODEPS_COMMAND_DIR_PATH =
      "buck-out/log/"
          + "2016-06-21_16h18m51s_autodepscommand_d09893d5-b11e-4e3f-a5bf-70c60a06896e/";

  @Test
  public void testRageNonInteractiveReport() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("rage", "--non-interactive").assertSuccess();
  }

  @Test
  public void testUpload() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();

    final AtomicReference<String> requestMethod = new AtomicReference<>();
    final AtomicReference<String> requestPath = new AtomicReference<>();
    final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    final String successMessage = "Upload successful";
    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpServletResponse)
                throws IOException, ServletException {
              httpServletResponse.setStatus(200);
              request.setHandled(true);

              if (request.getUri().getPath().equals("/status.php")) {
                return;
              }
              requestPath.set(request.getUri().getPath());
              requestMethod.set(request.getMethod());
              requestBody.set(ByteStreams.toByteArray(httpServletRequest.getInputStream()));
              try (DataOutputStream out =
                  new DataOutputStream(httpServletResponse.getOutputStream())) {
                out.writeBytes(successMessage);
              }
            }
          });
      httpd.start();

      RageConfig rageConfig =
          createRageConfig(httpd.getRootUri().getPort(), "", RageProtocolVersion.SIMPLE);
      ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
      Clock clock = new DefaultClock();
      DefectReporter reporter =
          new DefaultDefectReporter(
              filesystem, rageConfig, BuckEventBusFactory.newInstance(clock), clock);
      AutomatedReport automatedReport =
          new AutomatedReport(
              reporter,
              filesystem,
              new TestConsole(),
              TestBuildEnvironmentDescription.INSTANCE,
              new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
              false,
              rageConfig,
              Optional::empty);
      DefectSubmitResult defectSubmitResult = automatedReport.collectAndSubmitResult().get();

      assertThat(
          defectSubmitResult.getReportSubmitMessage(),
          Matchers.equalTo(Optional.of(successMessage)));
      assertThat(requestPath.get(), Matchers.equalTo(UPLOAD_PATH));
      assertThat(requestMethod.get(), Matchers.equalTo("POST"));

      filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
      Path report =
          filesystem.createTempFile(filesystem.getBuckPaths().getBuckOut(), "report", "zip");
      filesystem.writeBytesToPath(requestBody.get(), report);
      ZipInspector zipInspector = new ZipInspector(filesystem.resolve(report));
      zipInspector.assertFileExists("report.json");
      zipInspector.assertFileExists("buckconfig.local");
      zipInspector.assertFileExists("bucklogging.local.properties");
      zipInspector.assertFileExists(BUILD_COMMAND_DIR_PATH + "buck.log");
      zipInspector.assertFileExists(AUTODEPS_COMMAND_DIR_PATH + "buck.log");
      zipInspector.assertFileExists(BUILD_COMMAND_DIR_PATH + "buck-machine-log");
      zipInspector.assertFileExists(AUTODEPS_COMMAND_DIR_PATH + "buck-machine-log");
    }
  }

  @Test
  public void testExtraInfo() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();
    RageConfig rageConfig = createRageConfig(0, "python, extra.py", RageProtocolVersion.SIMPLE);
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    Console console = new TestConsole();
    CapturingDefectReporter defectReporter = new CapturingDefectReporter();
    AutomatedReport automatedReport =
        new AutomatedReport(
            defectReporter,
            filesystem,
            new TestConsole(),
            TestBuildEnvironmentDescription.INSTANCE,
            new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
            false,
            rageConfig,
            new DefaultExtraInfoCollector(
                rageConfig, filesystem, new DefaultProcessExecutor(console)));
    automatedReport.collectAndSubmitResult();
    DefectReport defectReport = defectReporter.getDefectReport();
    assertThat(defectReport.getExtraInfo(), Matchers.equalTo(Optional.of("Extra\n")));
    assertThat(
        FluentIterable.from(defectReport.getIncludedPaths()).transform(Object::toString),
        Matchers.hasItem(Matchers.endsWith("extra.txt")));
  }

  @Test
  public void testUploadFailure() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();

    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpServletResponse)
                throws IOException, ServletException {
              httpServletResponse.setStatus(500);
              request.setHandled(true);
            }
          });
      httpd.start();

      RageConfig rageConfig =
          createRageConfig(httpd.getRootUri().getPort(), "", RageProtocolVersion.SIMPLE);
      ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
      Clock clock = new DefaultClock();
      DefectReporter reporter =
          new DefaultDefectReporter(
              filesystem, rageConfig, BuckEventBusFactory.newInstance(clock), clock);
      AutomatedReport automatedReport =
          new AutomatedReport(
              reporter,
              filesystem,
              new TestConsole(),
              TestBuildEnvironmentDescription.INSTANCE,
              new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
              false,
              rageConfig,
              Optional::empty);

      DefectSubmitResult submitReport = automatedReport.collectAndSubmitResult().get();
      // If upload fails it should store the zip locally and inform the user.
      assertFalse(submitReport.getReportSubmitErrorMessage().get().isEmpty());
      ZipInspector zipInspector =
          new ZipInspector(filesystem.resolve(submitReport.getReportSubmitLocation().get()));
      assertEquals(zipInspector.getZipFileEntries().size(), 7);
    }
  }

  @Test
  public void testJsonUpload() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();

    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpResponse)
                throws IOException, ServletException {
              httpResponse.setStatus(200);
              request.setHandled(true);

              if (request.getUri().getPath().equals("/status.php")) {
                return;
              }

              RageJsonResponse json =
                  RageJsonResponse.of(
                      /* isRequestSuccessful */ true,
                      /* errorMessage */ Optional.empty(),
                      /* rageUrl */ Optional.of("http://remoteUrlToVisit"),
                      /* message */ Optional.of("This is supposed to be JSON."));
              try (DataOutputStream out = new DataOutputStream(httpResponse.getOutputStream())) {
                ObjectMappers.WRITER.writeValue(out, json);
              }
            }
          });
      httpd.start();

      RageConfig rageConfig =
          createRageConfig(httpd.getRootUri().getPort(), "", RageProtocolVersion.JSON);
      ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
      Clock clock = new DefaultClock();
      DefectReporter reporter =
          new DefaultDefectReporter(
              filesystem, rageConfig, BuckEventBusFactory.newInstance(clock), clock);
      AutomatedReport automatedReport =
          new AutomatedReport(
              reporter,
              filesystem,
              new TestConsole(),
              TestBuildEnvironmentDescription.INSTANCE,
              new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
              false,
              rageConfig,
              Optional::empty);

      DefectSubmitResult submitReport = automatedReport.collectAndSubmitResult().get();
      assertEquals("http://remoteUrlToVisit", submitReport.getReportSubmitLocation().get());
      assertEquals("This is supposed to be JSON.", submitReport.getReportSubmitMessage().get());
    }
  }

  private RageConfig createRageConfig(int port, String extraInfo, RageProtocolVersion version) {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    RageConfig.RAGE_SECTION,
                    ImmutableMap.of(
                        RageConfig.REPORT_UPLOAD_PATH_FIELD,
                        UPLOAD_PATH,
                        RageConfig.PROTOCOL_VERSION_FIELD,
                        version.name(),
                        "slb_server_pool",
                        "http://localhost:" + port,
                        RageConfig.EXTRA_INFO_COMMAND_FIELD,
                        extraInfo)))
            .build();
    return RageConfig.of(buckConfig);
  }

  private static class CapturingDefectReporter implements DefectReporter {
    private DefectReport defectReport = null;

    public DefectReport getDefectReport() {
      return Preconditions.checkNotNull(defectReport);
    }

    @Override
    public DefectSubmitResult submitReport(DefectReport defectReport) throws IOException {
      this.defectReport = defectReport;
      return DefectSubmitResult.builder()
          .setRequestProtocol(RageProtocolVersion.SIMPLE)
          .setReportSubmitLocation("")
          .build();
    }
  }
}
