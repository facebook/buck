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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.doctor.DefaultDefectReporter;
import com.facebook.buck.doctor.DefectReporter;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorJsonResponse;
import com.facebook.buck.doctor.config.DoctorProtocolVersion;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuleKeyLogFileUploaderTest {

  private final BuildId buildId = new BuildId();
  private final String rageReportId = "123456789";
  private final String rageUrl = "https://www.webpage.com/buck/rage/" + rageReportId;

  private ProjectFilesystem filesystem;
  private BuildReportConfig buildReportConfig;
  private Clock clock;
  private Path ruleKeyLogFile;

  private static final BuildEnvironmentDescription TEST_ENV_DESCRIPTION =
      BuildEnvironmentDescription.of(
          "test_user",
          "test_hostname",
          "test_os",
          1,
          1024L,
          Optional.of(false),
          "test_commit",
          "test_java_version",
          1);

  @Before
  public void setUp() throws IOException {
    TemporaryFolder tempDirectory = new TemporaryFolder();
    tempDirectory.create();
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempDirectory.getRoot().toPath());
    buildReportConfig = FakeBuckConfig.builder().build().getView(BuildReportConfig.class);
    clock = new DefaultClock();

    ruleKeyLogFile = tempDirectory.newFile(BuckConstant.RULE_KEY_LOGGER_FILE_NAME).toPath();
    String ruleKeyLogContent =
        "target\t//test/com/facebook/buck/testutil/integration:util\tbf90764fa7d161f6ac7988dfaeed4f1b6f7d03d1\n";
    filesystem.writeContentsToPath(ruleKeyLogContent, ruleKeyLogFile);
  }

  private class BuildReportEndpointHandler extends AbstractHandler {
    @Override
    public void handle(
        String s, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
        throws IOException {
      httpResponse.setStatus(200);
      request.setHandled(true);

      String requestBuildId = httpRequest.getParameter("uuid");
      String requestRageReportId = httpRequest.getParameter("rage_report_id");

      assertEquals(buildId.toString(), requestBuildId);
      assertEquals(rageReportId, requestRageReportId);

      httpResponse.setContentType("application/json");
      httpResponse.setCharacterEncoding("utf-8");

      try (DataOutputStream out = new DataOutputStream(httpResponse.getOutputStream())) {
        out.writeBytes("{}");
      }
    }
  }

  private class RageReportEndpointHandler extends AbstractHandler {
    @Override
    public void handle(
        String s, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
        throws IOException {
      httpResponse.setStatus(200);
      request.setHandled(true);

      String rageMsg = "This is supposed to be JSON.";

      httpResponse.setContentType("application/json");
      httpResponse.setCharacterEncoding("utf-8");

      DoctorJsonResponse json =
          DoctorJsonResponse.of(true, Optional.empty(), Optional.of(rageUrl), Optional.of(rageMsg));
      try (DataOutputStream out = new DataOutputStream(httpResponse.getOutputStream())) {
        ObjectMappers.WRITER.writeValue((DataOutput) out, json);
      }
    }
  }

  /**
   * Tests that RuleKeyLogFileUploader uploads a report to the rage report endpoint, and after that
   * uploads both the rage report id and the build id to the build report endpoint.
   */
  @Test
  public void uploadToRageReportAndBuildId() throws Exception {

    try (HttpdForTests httpdRageReportEndpoint = HttpdForTests.httpdForOkHttpTests();
        HttpdForTests httpdBuildReportEndpoint = HttpdForTests.httpdForOkHttpTests()) {

      httpdRageReportEndpoint.addHandler(new RageReportEndpointHandler());
      httpdRageReportEndpoint.start();

      httpdBuildReportEndpoint.addHandler(new BuildReportEndpointHandler());
      httpdBuildReportEndpoint.start();

      URI testRageReportEndpointURI = httpdRageReportEndpoint.getRootUri();
      DefectReporter reporter = createDefaultDefectReporter(testRageReportEndpointURI);

      long timeoutMs = buildReportConfig.getEndpointTimeoutMs();

      new RuleKeyLogFileUploader(
              reporter,
              TEST_ENV_DESCRIPTION,
              httpdBuildReportEndpoint.getRootUri().toURL(),
              timeoutMs,
              buildId)
          .uploadRuleKeyLogFile(ruleKeyLogFile);
    }
  }

  private DefectReporter createDefaultDefectReporter(URI testRageReportEndpointURI)
      throws Exception {
    String endpointUrl = testRageReportEndpointURI.toURL().toString();
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    DoctorConfig.DOCTOR_SECTION,
                    new ImmutableMap.Builder<String, String>()
                        .put(DoctorConfig.PROTOCOL_VERSION_FIELD, DoctorProtocolVersion.JSON.name())
                        .put(DoctorConfig.ENDPOINT_URL_FIELD, endpointUrl)
                        .put("slb_server_pool", endpointUrl)
                        .build()))
            .build();
    DoctorConfig doctorConfig = buckConfig.getView(DoctorConfig.class);

    return new DefaultDefectReporter(
        filesystem, doctorConfig, BuckEventBusForTests.newInstance(clock), clock);
  }
}
