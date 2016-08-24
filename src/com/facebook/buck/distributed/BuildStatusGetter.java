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
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.slb.ThriftService;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.IOException;

public abstract class BuildStatusGetter {
  private BuildStatusGetter() {
    // do not instantiate.
  }

  public static Optional<BuildJob> getBuildJob(
      BuildId buildId,
      ThriftService<FrontendRequest, FrontendResponse> service) throws IOException {
    FrontendRequest frontendRequest = createFrontendRequest(buildId);
    FrontendResponse frontendResponse = new FrontendResponse();
    service.makeRequest(frontendRequest, frontendResponse);
    if (frontendResponse.isWasSuccessful()) {
      Preconditions.checkState(frontendResponse.isSetBuildStatusResponse());
      Preconditions.checkState(frontendResponse.getBuildStatusResponse().isSetBuildJob());
      return Optional.of(frontendResponse.getBuildStatusResponse().getBuildJob());
    } else {
      return Optional.absent();
    }
  }

  public static FrontendRequest createFrontendRequest(BuildId buildId) {
    BuildStatusRequest buildStatusRequest = new BuildStatusRequest();
    buildStatusRequest.setBuildId(buildId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.BUILD_STATUS);
    frontendRequest.setBuildStatusRequest(buildStatusRequest);
    return frontendRequest;
  }
}
