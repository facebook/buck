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
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Test;

public class BuildReportUploaderTest {

  private final BuckConfig buckConfig =
      FakeBuckConfig.builder()
          .setSections(
              "[section1]\nfield1 = value1.1, value1.2\n[section2]\nfield2 = value2\nfield4 = value2")
          .build();

  private final FullVersionControlStats versionControlStats =
      FullVersionControlStats.builder()
          .setCurrentRevisionId("f00")
          .setBranchedFromMasterRevisionId("b47")
          .setBranchedFromMasterTS(0L)
          .setBaseBookmarks(ImmutableSet.of("remote/master"))
          .setPathsChangedInWorkingDirectory(ImmutableSet.of("hello.txt"))
          .build();

  private BuildId buildId = new BuildId();

  private class BuildReportHandler extends AbstractHandler {
    @Override
    public void handle(
        String s, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
        throws IOException {
      httpResponse.setStatus(200);
      request.setHandled(true);

      String uuid = request.getParameter("uuid");
      assertEquals(buildId.toString(), uuid);

      JsonNode reportReceived = ObjectMappers.READER.readTree(httpRequest.getParameter("data"));

      JsonNode currentConfig = reportReceived.get("currentConfig");
      assertNotNull(currentConfig);
      assertNotNull(currentConfig.get("rawConfig"));
      assertNotNull(currentConfig.get("configsMap"));

      JsonNode versionControlStats = reportReceived.get("versionControlStats");
      assertNotNull(versionControlStats);
      assertNotNull(versionControlStats.get("pathsChangedInWorkingDirectory"));
      assertNotNull(versionControlStats.get("currentRevisionId"));

      httpResponse.setContentType("application/json");
      httpResponse.setCharacterEncoding("utf-8");

      // this is an example json of what the real endpoint should return.
      String jsonResponse = "{ \"uri\": \"a.test.uri.com\"}";

      DataOutputStream out = new DataOutputStream(httpResponse.getOutputStream());
      out.writeBytes(jsonResponse);
    }
  }

  @Test
  public void uploadReportToTestServer() throws Exception {

    FullBuildReport reportToSend =
        ImmutableFullBuildReport.of(buckConfig.getConfig(), Optional.of(versionControlStats));

    try (HttpdForTests httpd = HttpdForTests.httpdForOkHttpTests()) {
      httpd.addHandler(new BuildReportHandler());
      httpd.start();

      URI testEndpointURI = httpd.getRootUri();
      long timeoutMs = buckConfig.getView(BuildReportConfig.class).getEndpointTimeoutMs();

      UploadResponse response =
          new BuildReportUploader(testEndpointURI.toURL(), timeoutMs, buildId)
              .uploadReport(reportToSend);

      Optional<String> uri = response.uri();

      assertEquals("a.test.uri.com", uri.get());
    }
  }
}
