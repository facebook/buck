/*
 * Copyright 2017-present Facebook, Inc.
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

import static com.facebook.buck.doctor.config.DoctorConfig.DEFAULT_REPORT_UPLOAD_PATH;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.doctor.config.BuildLogEntry;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorProtocolVersion;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

final class DoctorTestUtils {

  private DoctorTestUtils() {}

  private static final String WATCHMAN_DIAG_COMMAND = "watchman-diag";

  static DefectSubmitResult createDefectReport(
      ProjectWorkspace workspace,
      ImmutableSet<BuildLogEntry> buildLogEntries,
      UserInput userInput,
      DoctorConfig doctorConfig)
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = workspace.asCell().getFilesystem();
    Clock clock = new DefaultClock();
    TestConsole console = new TestConsole();

    WatchmanDiagReportCollector watchmanDiagReportCollector =
        new WatchmanDiagReportCollector(
            filesystem, WATCHMAN_DIAG_COMMAND, createFakeWatchmanDiagProcessExecutor(console));

    DefectReporter reporter =
        new DefaultDefectReporter(
            filesystem, doctorConfig, BuckEventBusForTests.newInstance(clock), clock);

    DoctorInteractiveReport report =
        new DoctorInteractiveReport(
            reporter,
            filesystem,
            console,
            userInput,
            Optional.empty(),
            TestBuildEnvironmentDescription.INSTANCE,
            new VersionControlStatsGenerator(new NoOpCmdLineInterface(), Optional.empty()),
            doctorConfig,
            new DefaultExtraInfoCollector(
                doctorConfig, filesystem, new DefaultProcessExecutor(console)),
            buildLogEntries,
            Optional.of(watchmanDiagReportCollector));

    return report.collectAndSubmitResult().get();
  }

  static DoctorReportHelper createDoctorHelper(
      ProjectWorkspace workspace, UserInput input, DoctorConfig doctorConfig) throws Exception {
    return new DoctorReportHelper(
        workspace.asCell().getFilesystem(), input, new TestConsole(), doctorConfig);
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

  static DoctorConfig createDoctorConfig(
      int port, String extraInfo, DoctorProtocolVersion version) {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    DoctorConfig.DOCTOR_SECTION,
                    ImmutableMap.of(
                        DoctorConfig.REPORT_UPLOAD_PATH_FIELD,
                        DEFAULT_REPORT_UPLOAD_PATH,
                        DoctorConfig.PROTOCOL_VERSION_FIELD,
                        version.name(),
                        DoctorConfig.ENDPOINT_URL_FIELD,
                        "http://localhost:" + port,
                        "slb_server_pool",
                        "http://localhost:" + port,
                        DoctorConfig.REPORT_EXTRA_INFO_COMMAND_FIELD,
                        extraInfo)))
            .build();
    return DoctorConfig.of(buckConfig);
  }

  public static class CapturingDefectReporter implements DefectReporter {
    private DefectReport defectReport = null;

    DefectReport getDefectReport() {
      return Objects.requireNonNull(defectReport);
    }

    @Override
    public DefectSubmitResult submitReport(DefectReport defectReport) {
      this.defectReport = defectReport;
      return DefectSubmitResult.builder()
          .setRequestProtocol(DoctorProtocolVersion.SIMPLE)
          .setReportSubmitLocation("")
          .build();
    }
  }
}
