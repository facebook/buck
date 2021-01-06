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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Schedules the background task that uploads a {@link FullBuildReport} */
public class BuildReportUpload {

  private static final Logger LOG = Logger.get(BuildReportUpload.class);

  private static final int VERSION_CONTROL_STATS_GENERATION_TIMEOUT = 3;

  private final TaskManagerCommandScope managerScope;
  private final BuildReportUploader buildReportUploader;

  BuildReportUpload(
      TaskManagerCommandScope managerScope, URL endpointURL, long timeout, BuildId buildId) {
    this.managerScope = managerScope;
    this.buildReportUploader = new BuildReportUploader(endpointURL, timeout, buildId);
  }

  /**
   * If reporting is not enabled in the config, or the endpoint url is empty, it will not run.
   *
   * @param managerScope the task manager scope to use for scheduling
   * @param vcStatsFuture the future that returns an optional FullVersionControlStats
   * @param buckConfig the config of the current run. This is added to the report and used to
   *     determine whether the report should be uploaded
   * @param buildId of the current Build
   */
  public static void runBuildReportUpload(
      TaskManagerCommandScope managerScope,
      ListenableFuture<Optional<FullVersionControlStats>> vcStatsFuture,
      BuckConfig buckConfig,
      BuildId buildId) {
    BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);

    URL endpointUrl = buildReportConfig.getEndpointUrl().get();

    BuildReportUpload buildReportUpload =
        new BuildReportUpload(
            managerScope, endpointUrl, buildReportConfig.getEndpointTimeoutMs(), buildId);

    buildReportUpload.uploadInBackground(buckConfig.getConfig(), vcStatsFuture);
  }

  /** Schedules background task to upload the report */
  private void uploadInBackground(
      Config config, ListenableFuture<Optional<FullVersionControlStats>> vcStatsFuture) {
    BuildReportUploadActionArgs args =
        ImmutableBuildReportUploadActionArgs.of(config, buildReportUploader, vcStatsFuture);

    BackgroundTask<BuildReportUploadActionArgs> task =
        BackgroundTask.of("BuildReportUpload", new BuildReportUploadAction(), args);

    managerScope.schedule(task);
  }

  /** {@link TaskAction} that uploads a Build Report */
  static class BuildReportUploadAction implements TaskAction<BuildReportUploadActionArgs> {
    @Override
    public void run(BuildReportUploadActionArgs args) {
      Optional<FullVersionControlStats> vcStats = Optional.empty();
      try {
        vcStats =
            args.getVcStatsFuture().get(VERSION_CONTROL_STATS_GENERATION_TIMEOUT, TimeUnit.SECONDS);

      } catch (InterruptedException | ExecutionException | TimeoutException e) {

        LOG.warn(
            e,
            "version control stats generation timed out,"
                + " was interrupted, or threw an exception");
      }

      if (!vcStats.isPresent()) {
        LOG.warn("Uploading build report without version control stats");
      }

      FullBuildReport buildReport = ImmutableFullBuildReport.of(args.getConfig(), vcStats);
      try {
        args.getBuildReportUploader().uploadReport(buildReport);
      } catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  /** Arguments to {@link BuildReportUploadAction}. */
  @BuckStyleValue
  abstract static class BuildReportUploadActionArgs {

    abstract Config getConfig();

    abstract BuildReportUploader getBuildReportUploader();

    abstract ListenableFuture<Optional<FullVersionControlStats>> getVcStatsFuture();
  }
}
