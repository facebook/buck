/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.build.report;

import static org.junit.Assert.*;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.google.common.io.CharStreams;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuildReportFileUploaderTest {

  private BuildId buildId = new BuildId();
  private String TRACE_FILE_KIND = "test_file_kind";
  private String TEST_FILE_NAME = "test_file";
  private String TEST_CONTENT = "rjcgtchjbhhhnjfbjdfjgilbehfvebue";

  private final BuckConfig buckConfig = FakeBuckConfig.builder().build();

  private class BuildReportFileHandler extends AbstractHandler {
    @Override
    public void handle(
        String s, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
        throws IOException {
      httpResponse.setStatus(200);
      request.setHandled(true);

      String uuid = request.getParameter("uuid");
      assertEquals(buildId.toString(), uuid);

      String traceFileKind = request.getParameter("trace_file_kind");
      assertEquals(TRACE_FILE_KIND, traceFileKind);

      String requestString =
          CharStreams.toString(new InputStreamReader(httpRequest.getInputStream()));

      // Ideally we would parse it as a proper multipart body request, but the HttpForTests'
      // servlets are not configured for parsing that, so let's just check at least that the
      // information is included.

      assertThat(
          "request contains file content", requestString, Matchers.containsString(TEST_CONTENT));

      assertThat(
          "request contains file name", requestString, Matchers.containsString(TEST_FILE_NAME));

      assertThat(
          "request contains form-data field name",
          requestString,
          Matchers.containsString("trace_file"));

      DataOutputStream out = new DataOutputStream(httpResponse.getOutputStream());
      out.writeBytes("{}");
    }
  }

  @Test
  public void uploadFileToTestServer() throws Exception {
    TemporaryFolder tempDirectory = new TemporaryFolder();
    tempDirectory.create();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tempDirectory.getRoot().toPath());

    Path fileToUpload = tempDirectory.newFile(TEST_FILE_NAME).toPath();
    filesystem.writeContentsToPath(TEST_CONTENT, fileToUpload);

    try (HttpdForTests httpd = HttpdForTests.httpdForOkHttpTests()) {
      httpd.addHandler(new BuildReportFileHandler());
      httpd.start();

      URI testEndpointURI = httpd.getRootUri();
      long timeoutMs = buckConfig.getView(BuildReportConfig.class).getEndpointTimeoutMs();

      new BuildReportFileUploader(testEndpointURI.toURL(), timeoutMs, buildId)
          .uploadFile(fileToUpload, TRACE_FILE_KIND);
    }
  }
}
