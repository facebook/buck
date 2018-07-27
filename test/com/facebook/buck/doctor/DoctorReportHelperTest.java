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

package com.facebook.buck.doctor;

import static com.facebook.buck.doctor.DoctorTestUtils.createDoctorConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorEndpointResponse;
import com.facebook.buck.doctor.config.DoctorProtocolVersion;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DoctorReportHelperTest {

  @Rule public TemporaryPaths tempFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();
  }

  @Test
  public void testErrorMessage() throws Exception {
    TestConsole console = new TestConsole();
    DoctorConfig doctorConfig = createDoctorConfig(0, "", DoctorProtocolVersion.SIMPLE);
    DoctorReportHelper helper =
        new DoctorReportHelper(
            workspace.asCell().getFilesystem(),
            (new UserInputFixture("0")).getUserInput(),
            console,
            doctorConfig);

    String errorMessage = "This is an error message.";
    DoctorEndpointResponse response =
        DoctorEndpointResponse.of(Optional.of(errorMessage), ImmutableList.of());

    helper.presentResponse(response);
    assertEquals("=> " + errorMessage + System.lineSeparator(), console.getTextWrittenToStdOut());
  }

  @Test
  public void testNoAvailableSuggestions() throws Exception {
    TestConsole console = new TestConsole();
    DoctorConfig doctorConfig = createDoctorConfig(0, "", DoctorProtocolVersion.SIMPLE);
    DoctorReportHelper helper =
        new DoctorReportHelper(
            workspace.asCell().getFilesystem(),
            (new UserInputFixture("0")).getUserInput(),
            console,
            doctorConfig);

    DoctorEndpointResponse response =
        DoctorEndpointResponse.of(Optional.empty(), ImmutableList.of());

    helper.presentResponse(response);
    assertEquals(
        String.format("%n:: No available suggestions right now.%n%n"),
        console.getTextWrittenToStdOut());
  }

  @Test
  public void testIssueCategoryThatDoesNotPromptsInput() throws Exception {
    TestConsole console = new TestConsole();
    DoctorConfig doctorConfig = createDoctorConfig(0, "", DoctorProtocolVersion.SIMPLE);
    DoctorReportHelper helper =
        new DoctorReportHelper(
            workspace.asCell().getFilesystem(),
            (new UserInputFixture("1")).getUserInput(),
            console,
            doctorConfig);

    Optional<String> issue = helper.promptForIssue();
    assertThat(issue.get(), Matchers.equalTo("Cache error"));
  }
}
