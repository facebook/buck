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
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogRecord;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftService;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class DistBuildService {

  private static final Logger LOG = Logger.get(DistBuildService.class);

  private static final int MAX_BUILD_DURATION_MILLIS = 10000;
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");

  private final ThriftService<FrontendRequest, FrontendResponse> service;
  private final BuckEventBus eventBus;

  public DistBuildService(
      ThriftService<FrontendRequest, FrontendResponse> service,
      BuckEventBus eventBus) {
    this.service = service;
    this.eventBus = eventBus;
  }

  public void submitJob() throws IOException {

    // Tell server to create the build and get the build id.
    CreateBuildRequest createTimeRequest = new CreateBuildRequest();
    createTimeRequest.setCreateTimestampMillis(System.currentTimeMillis());
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createTimeRequest);
    FrontendResponse response = new FrontendResponse();
    service.makeRequest(request, response);
    Preconditions.checkState(response.getType().equals(FrontendRequestType.CREATE_BUILD));
    BuildJob job = response.getCreateBuildResponse().getBuildJob();
    final BuildId id = job.getBuildId();
    LOG.info("Created job. Build id = " + id.getId());
    logDebugInfo(job);

    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setBuildId(id);
    request.clear();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuild(startRequest);
    response.clear();
    service.makeRequest(request, response);
    Preconditions.checkState(response.getType().equals(FrontendRequestType.START_BUILD));
    job = response.getStartBuild().getBuildJob();
    Preconditions.checkState(job.getBuildId().equals(id));
    LOG.info("Started job. Build status: " + job.getStatus().toString());
    logDebugInfo(job);

    // Create the poll for buildStatus request.
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setBuildId(id);
    request.clear();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatus(statusRequest);
    Stopwatch stopwatch = Stopwatch.createStarted();

    // Keep polling until the build is complete or failed.
    do {
      response.clear();
      service.makeRequest(request, response);
      Preconditions.checkState(response.getType().equals(FrontendRequestType.BUILD_STATUS));
      job = response.getBuildStatus().getBuildJob();
      Preconditions.checkState(job.getBuildId().equals(id));
      LOG.info("Got build status: " + job.getStatus().toString());

      DistBuildStatus distBuildStatus =
          prepareStatusFromJob(job)
          .setETAMillis(MAX_BUILD_DURATION_MILLIS - stopwatch.elapsed(TimeUnit.MILLISECONDS))
          .build();
      eventBus.post(new DistBuildStatusEvent(distBuildStatus));

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.error(e, "BuildStatus polling sleep call has been interrupted unexpectedly.");
      }
    } while (!(job.getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY) ||
               job.getStatus().equals(BuildStatus.FAILED)));

    LOG.info("Build was " +
        (response.isWasSuccessful() ? "" : "not ") +
        "successful!");
    if (response.isSetErrorMessage()) {
      LOG.error("Error msg: " + response.getErrorMessage());
    }
    logDebugInfo(job);

    DistBuildStatus distBuildStatus = prepareStatusFromJob(job).setETAMillis(0).build();
    eventBus.post(new DistBuildStatusEvent(distBuildStatus));
  }

  private DistBuildStatus.Builder prepareStatusFromJob(BuildJob job) {
    Optional<List<LogRecord>> logBook = Optional.absent();
    Optional<String> lastLine = Optional.absent();
    if (job.isSetDebug() && job.getDebug().isSetLogBook())  {
      logBook = Optional.of(job.getDebug().getLogBook());
      if (logBook.get().size() > 0) {
        lastLine = Optional.of(logBook.get().get(logBook.get().size() - 1).getName());
      }
    }

    return DistBuildStatus.builder()
        .setStatus(job.getStatus())
        .setMessage(lastLine)
        .setLogBook(logBook);
  }

  private void logDebugInfo(BuildJob job) {
    if (job.isSetDebug() && job.getDebug().getLogBook().size() > 0) {
      LOG.debug("Received debug info: ");
      for (LogRecord log : job.getDebug().getLogBook()) {
        LOG.debug(DATE_FORMAT.format(new Date(log.getTimestampMillis())) + log.getName());
      }
    }
  }
}
