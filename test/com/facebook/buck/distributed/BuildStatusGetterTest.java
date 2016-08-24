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
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.slb.ThriftService;
import com.google.common.base.Optional;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class BuildStatusGetterTest {

  @Test
  public void testRequestContainsBuildId() {
    BuildId buildId = createBuildId("topspin");
    FrontendRequest request = BuildStatusGetter.createFrontendRequest(buildId);
    Assert.assertEquals(buildId, request.getBuildStatusRequest().getBuildId());
  }

  @Test
  public void testServiceIsCalled() throws IOException {
    BuildId buildId = createBuildId("slice");
    FrontendService mockService = EasyMock.createNiceMock(FrontendService.class);
    mockService.makeRequest(
        EasyMock.anyObject(FrontendRequest.class),
        EasyMock.anyObject(FrontendResponse.class));
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockService);

    Optional<BuildJob> response = BuildStatusGetter.getBuildJob(buildId, mockService);
    EasyMock.verify(mockService);
    Assert.assertFalse(response.isPresent());
  }

  private static BuildId createBuildId(String id) {
    BuildId buildId = new BuildId();
    buildId.setId(id);
    return buildId;
  }

  private interface FrontendService extends ThriftService<FrontendRequest, FrontendResponse> {
  }
}
