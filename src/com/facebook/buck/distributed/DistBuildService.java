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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.HttpLoadBalancer;
import com.facebook.buck.slb.NoHealthyServersException;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;


public class DistBuildService {
  private static final Logger LOG = Logger.get(DistBuildService.class);
  private static final int BUILD_DURATION_MS = 10000;

  private final DistBuildConfig distBuildConfig;
  private final String frontendUrl;
  private final BuckEventBus eventBus;

  public DistBuildService(DistBuildConfig distBuildConfig, BuckEventBus eventBus)
      throws NoHealthyServersException {
    this.distBuildConfig = distBuildConfig;
    this.eventBus = eventBus;
    try {
      HttpLoadBalancer client =
          distBuildConfig.getFrontendConfig().createHttpClientSideSlb(new DefaultClock(), eventBus);
      this.frontendUrl = client.getBestServer().toString();
    } catch (NoHealthyServersException ex)  {
      LOG.error("No healthy distributed build frontend server found. " +
          "Do you have them listed in your .buckconfig or .buckconfig.local?");
      throw ex;
    }
  }

  public DistBuildService(DistBuildConfig distBuildConfig, BuckEventBus eventBus,
      String frontendUrl) {
    Preconditions.checkNotNull(frontendUrl);
    Preconditions.checkState(!frontendUrl.isEmpty(),
        "Distributed build frontend URL cannot be empty.");
    this.distBuildConfig = distBuildConfig;
    this.eventBus = eventBus;
    this.frontendUrl = frontendUrl;
  }

  public String getFrontendUrl()  {
    return this.frontendUrl;
  }

  public void submitJob() {
    LOG.verbose(String.format("DistBuildConfig: [%s]", distBuildConfig.toString()));

    StartBuildRequest startTime = new StartBuildRequest();
    BuildStatusRequest status = new BuildStatusRequest();
    FrontendRequest request = new FrontendRequest();
    FrontendResponse response = new FrontendResponse();
    BuildJob job;
    BuildId id;

    startTime.setStartTimestampMillis(System.currentTimeMillis());

    try {
      ThriftOverHttp thriftClient =
          new ThriftOverHttp(frontendUrl, ThriftOverHttp.Encoding.json);

      // tell server to start build
      request.setType(FrontendRequestType.START_BUILD);
      request.setStartBuild(startTime);
      thriftClient.writeAndSend(request);

      // get build id
      thriftClient.read(response);
      Preconditions.checkState(response.getType().equals(FrontendRequestType.START_BUILD));
      job = response.getStartBuild().getBuildJob();
      id = job.getBuildId();
      LOG.info("Submitted job. Build id = " + id.getId());
      logDebugInfo(job);

      // poll for buildStatus
      status.setBuildId(id);
      request.clear();
      request.setType(FrontendRequestType.BUILD_STATUS);
      request.setBuildStatus(status);
      Stopwatch stopwatch = Stopwatch.createStarted();

      do {
        thriftClient.writeAndSend(request);

        // check response
        response.clear();
        thriftClient.read(response);
        Preconditions.checkState(response.getType().equals(FrontendRequestType.BUILD_STATUS));
        job = response.getBuildStatus().getBuildJob();
        Preconditions.checkState(job.getBuildId().equals(id));
        LOG.info("Got build status: " + job.getStatus().toString());

        eventBus.post(new DistBuildStatusEvent(
            DistBuildStatus.builder()
                .setETAMillis(BUILD_DURATION_MS - stopwatch.elapsed(TimeUnit.MILLISECONDS))
                .setStatus(job.getStatus().toString())
                .setMessage(getLastLogLine(job))
                .build()));

        Thread.sleep(1000);
      } while (!(job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ||
                 job.getStatus().equals(BuildStatus.FAILED)));

      // log status
      LOG.info("Build was " +
          (response.isWasSuccessful() ? "" : "not ") +
          "successful!");
      if (response.isSetErrorMessage()) {
        LOG.error("Error msg: " + response.getErrorMessage());
      }
      logDebugInfo(job);

      eventBus.post(new DistBuildStatusEvent(
          DistBuildStatus.builder()
              .setStatus(job.getStatus().toString())
              .setETAMillis(0)
              .setMessage(getLastLogLine(job))
              .build()));

      thriftClient.close();
    } catch (Exception e) {
      LOG.error(e);
    }
  }

  private Optional<String> getLastLogLine(BuildJob job)  {
    if (job.isSetDebug() && job.getDebug().getLogBook().size() > 0) {
      return Optional.of(
          job.getDebug().getLogBook().get(job.getDebug().getLogBook().size() - 1).getName());
    }
    return Optional.absent();
  }

  private void logDebugInfo(BuildJob job) {
    DateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
    if (job.isSetDebug() && job.getDebug().getLogBook().size() > 0) {
      LOG.debug("Received debug info: ");
      for (LogRecord log : job.getDebug().getLogBook()) {
        Date date = new Date(log.getTimestampMillis());
        String dateString = format.format(date) + "." +
            String.format("%03d", log.getTimestampMillis() % 1000);
        LOG.debug("[" + dateString + "] " + log.getName());
      }
    }
  }
}
