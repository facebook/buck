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

import static org.junit.Assert.assertThat;

import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class InteractiveReportIntegrationTest {

  private static final String BUILD_PATH =
      "buck-out/log/" + "2016-06-21_16h16m24s_buildcommand_ac8bd626-6137-4747-84dd-5d4f215c876c/";

  private ProjectWorkspace traceWorkspace;
  private String tracePath;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    tracePath = BUILD_PATH + "file.trace";
    traceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    traceWorkspace.setUp();
    traceWorkspace.writeContentsToPath(new String(new char[32 * 1024]), tracePath);
  }

  @Test
  public void testReport() throws Exception {
    UserInputFixture userInputFixture = new UserInputFixture("0");
    DoctorConfig doctorConfig = DoctorConfig.of(traceWorkspace.asCell().getBuckConfig());
    DoctorReportHelper helper =
        DoctorTestUtils.createDoctorHelper(
            traceWorkspace, userInputFixture.getUserInput(), doctorConfig);
    BuildLogHelper buildLogHelper = new BuildLogHelper(traceWorkspace.asCell().getFilesystem());
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DefectSubmitResult report =
        DoctorTestUtils.createDefectReport(
            traceWorkspace,
            ImmutableSet.of(entry.get()),
            userInputFixture.getUserInput(),
            doctorConfig);
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists("report.json");
    zipInspector.assertFileExists("buckconfig.local");
    zipInspector.assertFileExists(BUILD_PATH + "buck-machine-log");
    zipInspector.assertFileExists(BUILD_PATH + "buck.log");
    zipInspector.assertFileExists(BUILD_PATH + "file.trace");
  }

  @Test
  public void testTraceInReport() throws Exception {
    UserInputFixture userInputFixture = new UserInputFixture("0");
    DoctorConfig doctorConfig = DoctorConfig.of(traceWorkspace.asCell().getBuckConfig());
    DoctorReportHelper helper =
        DoctorTestUtils.createDoctorHelper(
            traceWorkspace, userInputFixture.getUserInput(), doctorConfig);
    BuildLogHelper buildLogHelper = new BuildLogHelper(traceWorkspace.asCell().getFilesystem());
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DefectSubmitResult report =
        DoctorTestUtils.createDefectReport(
            traceWorkspace,
            ImmutableSet.of(entry.get()),
            userInputFixture.getUserInput(),
            doctorConfig);
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists(tracePath);
  }

  @Test
  public void testTraceRespectReportSize() throws Exception {
    UserInputFixture userInputFixture = new UserInputFixture("0");
    DoctorConfig doctorConfig = DoctorConfig.of(traceWorkspace.asCell().getBuckConfig());
    DoctorReportHelper helper =
        DoctorTestUtils.createDoctorHelper(
            traceWorkspace, userInputFixture.getUserInput(), doctorConfig);
    BuildLogHelper buildLogHelper = new BuildLogHelper(traceWorkspace.asCell().getFilesystem());
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DefectSubmitResult report =
        DoctorTestUtils.createDefectReport(
            traceWorkspace,
            ImmutableSet.of(entry.get()),
            userInputFixture.getUserInput(),
            doctorConfig);
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    // The second command was more recent, so its file should be included.
    zipInspector.assertFileExists(tracePath);
  }

  @Test
  public void testLocalConfigurationReport() throws Exception {
    UserInputFixture userInputFixture = new UserInputFixture("0");
    DoctorConfig doctorConfig = DoctorConfig.of(traceWorkspace.asCell().getBuckConfig());
    DoctorReportHelper helper =
        DoctorTestUtils.createDoctorHelper(
            traceWorkspace, userInputFixture.getUserInput(), doctorConfig);
    BuildLogHelper buildLogHelper = new BuildLogHelper(traceWorkspace.asCell().getFilesystem());
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DefectSubmitResult report =
        DoctorTestUtils.createDefectReport(
            traceWorkspace,
            ImmutableSet.of(entry.get()),
            userInputFixture.getUserInput(),
            doctorConfig);
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    assertThat(
        zipInspector.getZipFileEntries(),
        Matchers.hasItems("buckconfig.local", "bucklogging.local.properties"));
  }

  @Test
  public void testWatchmanDiagReport() throws Exception {
    UserInputFixture userInputFixture = new UserInputFixture("0\n\n\ny");
    DoctorConfig doctorConfig = DoctorConfig.of(traceWorkspace.asCell().getBuckConfig());
    DoctorReportHelper helper =
        DoctorTestUtils.createDoctorHelper(
            traceWorkspace, userInputFixture.getUserInput(), doctorConfig);
    BuildLogHelper buildLogHelper = new BuildLogHelper(traceWorkspace.asCell().getFilesystem());
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DefectSubmitResult report =
        DoctorTestUtils.createDefectReport(
            traceWorkspace,
            ImmutableSet.of(entry.get()),
            userInputFixture.getUserInput(),
            doctorConfig);
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    assertThat(
        zipInspector.getZipFileEntries(),
        Matchers.hasItem(Matchers.stringContainsInOrder("watchman-diag-report")));
  }
}
