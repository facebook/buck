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

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class InteractiveReportIntegrationTest {

  private static final String BUILD_PATH =
      "buck-out/log/" + "2016-06-21_16h16m24s_buildcommand_ac8bd626-6137-4747-84dd-5d4f215c876c/";
  private static final String DEPS_PATH =
      "buck-out/log/"
          + "2016-06-21_16h18m51s_autodepscommand_d09893d5-b11e-4e3f-a5bf-70c60a06896e/";
  private static final String WATCHMAN_DIAG_COMMAND = "watchman-diag";
  private static final ImmutableMap<String, String> TIMESTAMPS =
      ImmutableMap.of(
          BUILD_PATH, "2016-06-21T16:16:24.00Z",
          DEPS_PATH, "2016-06-21T16:18:51.00Z");

  private ProjectWorkspace traceWorkspace;
  private String tracePath1;
  private String tracePath2;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    tracePath1 = BUILD_PATH + "file.trace";
    tracePath2 = DEPS_PATH + "file.trace";
    traceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    traceWorkspace.setUp();
    traceWorkspace.writeContentsToPath(new String(new char[32 * 1024]), tracePath1);
    traceWorkspace.writeContentsToPath(new String(new char[64 * 1024]), tracePath2);

    ProjectFilesystem filesystem = traceWorkspace.asCell().getFilesystem();
    for (Map.Entry<String, String> timestampEntry : TIMESTAMPS.entrySet()) {
      for (Path path : filesystem.getDirectoryContents(Paths.get(timestampEntry.getKey()))) {
        filesystem.setLastModifiedTime(
            path, FileTime.from(Instant.parse(timestampEntry.getValue())));
      }
    }
  }

  @Test
  public void testReport() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "report", temporaryFolder);
    workspace.setUp();

    DefectSubmitResult report =
        createDefectReport(
            workspace, new ByteArrayInputStream("0,1\nreport text\n".getBytes("UTF-8")));

    Path reportFile =
        workspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists("report.json");
    zipInspector.assertFileExists(BUILD_PATH + "buck.log");
    zipInspector.assertFileExists(DEPS_PATH + "buck.log");
  }

  @Test
  public void testTraceInReport() throws Exception {
    DefectSubmitResult report =
        createDefectReport(
            traceWorkspace, new ByteArrayInputStream("1\nreport text\n".getBytes("UTF-8")));
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists(tracePath1);
  }

  @Test
  public void testTraceRespectReportSize() throws Exception {
    DefectSubmitResult report =
        createDefectReport(
            traceWorkspace, new ByteArrayInputStream("0,1\nreport text\n".getBytes("UTF-8")));
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    // The second command was more recent, so its file should be included.
    zipInspector.assertFileExists(tracePath2);
    zipInspector.assertFileDoesNotExist(tracePath1);
  }

  @Test
  public void testLocalConfigurationReport() throws Exception {
    DefectSubmitResult report =
        createDefectReport(
            traceWorkspace, new ByteArrayInputStream("0,1\nreport text\n\n".getBytes("UTF-8")));
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    assertThat(
        zipInspector.getZipFileEntries(),
        Matchers.hasItems("buckconfig.local", "bucklogging.local.properties"));
  }

  @Test
  public void testWatchmanDiagReport() throws Exception {
    DefectSubmitResult report =
        createDefectReport(
            traceWorkspace, new ByteArrayInputStream("0,1\nreport text\n\n\n".getBytes("UTF-8")));
    Path reportFile =
        traceWorkspace.asCell().getFilesystem().resolve(report.getReportSubmitLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    assertThat(
        zipInspector.getZipFileEntries(),
        Matchers.hasItem(Matchers.stringContainsInOrder("watchman-diag-report")));
  }

  private static FakeProcessExecutor createFakeWatchmanDiagProcessExecutor(Console console) {
    return new FakeProcessExecutor(
        params -> {
          if (params.getCommand().get(0).equals(WATCHMAN_DIAG_COMMAND)) {
            return new FakeProcess(0, "fake watchman diag", "");
          } else {
            return new FakeProcess(33);
          }
        },
        console);
  }

  private static DefectSubmitResult createDefectReport(
      ProjectWorkspace workspace, ByteArrayInputStream inputStream)
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = workspace.asCell().getFilesystem();
    RageConfig rageConfig = RageConfig.of(workspace.asCell().getBuckConfig());
    Clock clock = new DefaultClock();
    ExtraInfoCollector extraInfoCollector = Optional::empty;
    TestConsole console = new TestConsole();
    DefectReporter defectReporter =
        new DefaultDefectReporter(
            filesystem, rageConfig, BuckEventBusFactory.newInstance(clock), clock);
    WatchmanDiagReportCollector watchmanDiagReportCollector =
        new WatchmanDiagReportCollector(
            filesystem, WATCHMAN_DIAG_COMMAND, createFakeWatchmanDiagProcessExecutor(console));
    InteractiveReport interactiveReport =
        new InteractiveReport(
            defectReporter,
            filesystem,
            console,
            inputStream,
            TestBuildEnvironmentDescription.INSTANCE,
            new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
            rageConfig,
            extraInfoCollector,
            Optional.of(watchmanDiagReportCollector));
    return interactiveReport.collectAndSubmitResult().get();
  }
}
