/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.support.build.report;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.util.config.Config;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;

/** Schedules the background task that uploads a {@link FullBuildReport} */
public class BuildReportUpload {

  private static final Logger LOG = Logger.get(BuildReportUpload.class);

  private final TaskManagerCommandScope managerScope;
  private final FullBuildReport report;

  BuildReportUpload(TaskManagerCommandScope managerScope, Config config, BuildId buildId) {
    this.report = new ImmutableFullBuildReport(config, buildId);
    this.managerScope = managerScope;
  }

  /**
   * If reporting is not enabled in the config, or the endpoint url is empty, it will not run.
   *
   * @param managerScope the task manager scope to use for scheduling
   * @param buckConfig the config of the current run. This is added to the report and used to
   *     determine whether the report should be uploaded
   */
  public static void runBuildReportUpload(
      TaskManagerCommandScope managerScope, BuckConfig buckConfig, BuildId buildId) {
    BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);

    if (!buildReportConfig.getEnabled()) {
      LOG.info("Build report is not enabled");
      return;
    }

    Optional<URL> endpointUrl = buildReportConfig.getEndpointUrl();

    if (!endpointUrl.isPresent()) {
      LOG.error("Build report is enabled but its endpoint url is not configured");
      return;
    }

    new BuildReportUpload(managerScope, buckConfig.getConfig(), buildId)
        .uploadInBackground(endpointUrl.get(), buildReportConfig.getEndpointTimeoutMs());
  }

  /** Schedules background task to upload the report */
  private void uploadInBackground(URL endpointURL, long timeout) {
    BuildReportUploadActionArgs args =
        new ImmutableBuildReportUploadActionArgs(this.report, endpointURL, timeout);

    BackgroundTask<BuildReportUploadActionArgs> task =
        ImmutableBackgroundTask.of("BuildReportUpload", new BuildReportUploadAction(), args);

    managerScope.schedule(task);
  }

  /** {@link TaskAction} that uploads a Build Report */
  static class BuildReportUploadAction implements TaskAction<BuildReportUploadActionArgs> {
    @Override
    public void run(BuildReportUploadActionArgs args) {
      try {
        BuildReportUploader.uploadReport(
            args.getBuildReport(), args.getUploadURL(), args.getTimeout());
      } catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  /** Arguments to {@link BuildReportUploadAction}. */
  @BuckStyleValue
  abstract static class BuildReportUploadActionArgs {

    abstract FullBuildReport getBuildReport();

    abstract URL getUploadURL();

    abstract long getTimeout();
  }
}
