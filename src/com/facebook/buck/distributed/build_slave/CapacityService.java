/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.BuildSlaveRequest;
import com.facebook.buck.distributed.thrift.BuildSlaveRequestType;
import com.facebook.buck.distributed.thrift.BuildSlaveResponse;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.GetAllAvailableCapacityRequest;
import com.facebook.buck.distributed.thrift.GetAllAvailableCapacityResponse;
import com.facebook.buck.distributed.thrift.ObtainAllAvailableCapacityRequest;
import com.facebook.buck.distributed.thrift.ObtainAllAvailableCapacityResponse;
import com.facebook.buck.distributed.thrift.ObtainCapacityRequest;
import com.facebook.buck.distributed.thrift.ReturnCapacityRequest;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import java.io.IOException;

/** Handles communication with the build slave */
public class CapacityService {
  protected final Logger LOG = Logger.get(getClass());

  private final BuildSlaveService buildSlaveService;
  private final BuildSlaveRunId buildSlaveRunId;

  public CapacityService(BuildSlaveService buildSlaveService, BuildSlaveRunId buildSlaveRunId) {
    this.buildSlaveService = buildSlaveService;
    this.buildSlaveRunId = buildSlaveRunId;
  }

  public int getAllAvailableCapacity() throws IOException {
    GetAllAvailableCapacityRequest getCapacityRequest = new GetAllAvailableCapacityRequest();

    BuildSlaveRequest request = new BuildSlaveRequest();
    request.setType(BuildSlaveRequestType.GET_ALL_AVAILABLE_CAPACITY);
    request.setGetAllAvailableCapacityRequest(getCapacityRequest);

    LOG.info("Fetching available capacity.");

    BuildSlaveResponse response = makeRequestChecked(request);
    GetAllAvailableCapacityResponse getAvailableCapacityResponse =
        response.getGetAllAvailableCapacityResponse();
    return getAvailableCapacityResponse.availableCapacity;
  }

  public int obtainAllAvailableCapacity() throws IOException {
    ObtainAllAvailableCapacityRequest obtainCapacityRequest =
        new ObtainAllAvailableCapacityRequest();
    obtainCapacityRequest.setBuildSlaveRunId(buildSlaveRunId);

    BuildSlaveRequest request = new BuildSlaveRequest();
    request.setType(BuildSlaveRequestType.OBTAIN_ALL_AVAILABLE_CAPACITY);
    request.setObtainAllAvailableCapacityRequest(obtainCapacityRequest);

    LOG.info("Reserving available capacity.");

    BuildSlaveResponse response = makeRequestChecked(request);
    ObtainAllAvailableCapacityResponse obtainCapacityResponse =
        response.getObtainAllAvailableCapacityResponse();
    return obtainCapacityResponse.obtainedCapacity;
  }

  public void obtainCapacity(int size) {
    ObtainCapacityRequest obtainCapacityRequest = new ObtainCapacityRequest();
    obtainCapacityRequest.setBuildSlaveRunId(buildSlaveRunId);
    obtainCapacityRequest.setCapacity(size);

    BuildSlaveRequest request = new BuildSlaveRequest();
    request.setType(BuildSlaveRequestType.OBTAIN_CAPACITY);
    request.setObtainCapacityRequest(obtainCapacityRequest);

    LOG.info(String.format("Committing capacity: [%d].", size));

    try {
      makeRequestChecked(request);
    } catch (IOException e) {
      LOG.error(e, String.format("Failed to commit capacity: [%d]", size));
    }
  }

  public void returnCapacity(int size) throws IOException {
    ReturnCapacityRequest returnCapacityRequest = new ReturnCapacityRequest();
    returnCapacityRequest.setBuildSlaveRunId(buildSlaveRunId);
    returnCapacityRequest.setCapacity(size);

    BuildSlaveRequest request = new BuildSlaveRequest();
    request.setType(BuildSlaveRequestType.RETURN_CAPACITY);
    request.setReturnCapacityRequest(returnCapacityRequest);

    LOG.info(String.format("Returning capacity: [%d]", size));

    makeRequestChecked(request);
  }

  private BuildSlaveResponse makeRequestChecked(BuildSlaveRequest request) throws IOException {
    BuildSlaveResponse response = buildSlaveService.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(
          String.format(
              "Build slave request of type [%s] failed with error message [%s].",
              request.getType().toString(), response.getErrorMessage()));
    }
    return response;
  }
}
