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

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.io.ByteStreams;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RageCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testRageNonInteractiveReport() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "interactive_report", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("rage", "--non-interactive").assertSuccess();
  }

  @Test
  public void testUpload() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "interactive_report", temporaryFolder);
    workspace.setUp();

    final AtomicReference<String> requestMethod = new AtomicReference<>();
    final AtomicReference<String> requestPath = new AtomicReference<>();
    final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpServletResponse) throws IOException, ServletException {
              requestPath.set(request.getUri().getPath());
              requestMethod.set(request.getMethod());
              requestBody.set(ByteStreams.toByteArray(httpServletRequest.getInputStream()));
              httpServletResponse.setStatus(200);
              try (DataOutputStream out =
                       new DataOutputStream(httpServletResponse.getOutputStream())) {
                out.writeBytes("Upload successful");
              }
            }
          });
      httpd.start();


      RageConfig config = RageConfig.builder()
          .setReportUploadUri(httpd.getUri("/rage"))
          .build();
      ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
      ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
      DefectReporter reporter = new DefectReporter(
          filesystem,
          objectMapper,
          config);
      AutomatedReport automatedReport = new AutomatedReport(
          reporter,
          filesystem,
          new CapturingPrintStream(),
          TestBuildEnvironmentDescription.INSTANCE,
          VcsInfoCollector.create(new NoOpCmdLineInterface()));
      DefectSubmitResult defectSubmitResult = automatedReport.collectAndSubmitResult();

      assertThat(
          defectSubmitResult.getReportSubmitMessage(),
          Matchers.equalTo(Optional.of("Upload successful")));
      assertThat(
          requestMethod.get(),
          Matchers.equalTo("POST"));
      assertThat(
          requestPath.get(),
          Matchers.equalTo("/rage"));

      filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
      Path report =
          filesystem.createTempFile(filesystem.getBuckPaths().getBuckOut(), "report", "zip");
      filesystem.writeBytesToPath(requestBody.get(), report);
      ZipInspector zipInspector = new ZipInspector(filesystem.resolve(report));
      zipInspector.assertFileExists("report.json");
      zipInspector.assertFileExists("buck-out/log/buck-0.log");
      zipInspector.assertFileExists("buck-out/log/buck-1.log");
    }
  }

  @Test
  public void testUploadFailure() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "interactive_report", temporaryFolder);
    workspace.setUp();

    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpServletResponse) throws IOException, ServletException {
              httpServletResponse.setStatus(500);
            }
          });
      httpd.start();


      RageConfig config = RageConfig.builder()
          .setReportUploadUri(httpd.getUri("/rage"))
          .build();
      ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
      ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
      DefectReporter reporter = new DefectReporter(
          filesystem,
          objectMapper,
          config);
      AutomatedReport automatedReport = new AutomatedReport(
          reporter,
          filesystem,
          new CapturingPrintStream(),
          TestBuildEnvironmentDescription.INSTANCE,
          VcsInfoCollector.create(new NoOpCmdLineInterface()));

      expectedException.expect(HumanReadableException.class);
      automatedReport.collectAndSubmitResult();
    }
  }
}
