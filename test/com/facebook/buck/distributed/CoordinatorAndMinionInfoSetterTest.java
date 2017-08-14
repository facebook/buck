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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildModeInfo;
import com.facebook.buck.distributed.thrift.StampedeId;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class CoordinatorAndMinionInfoSetterTest {

  @Test
  public void testCoordinatorEventListener() throws IOException {
    DistBuildService service = EasyMock.createMock(DistBuildService.class);
    StampedeId stampedeId = new StampedeId().setId("topspin");
    String minionQueue = "super_minion_queue";
    CoordinatorAndMinionInfoSetter eventListener =
        new CoordinatorAndMinionInfoSetter(service, stampedeId, minionQueue);
    Assert.assertNotNull(eventListener);
    int port = 33;
    String address = "hidden.but.cool.address";
    service.setCoordinator(EasyMock.eq(stampedeId), EasyMock.eq(port), EasyMock.eq(address));
    EasyMock.expectLastCall().once();

    int numberOfMinions = 42;
    BuildJob buildJob =
        new BuildJob().setBuildModeInfo(new BuildModeInfo().setNumberOfMinions(numberOfMinions));
    EasyMock.expect(service.getCurrentBuildJobState(EasyMock.eq(stampedeId)))
        .andReturn(buildJob)
        .once();

    service.enqueueMinions(
        EasyMock.eq(stampedeId), EasyMock.eq(numberOfMinions), EasyMock.eq(minionQueue));
    EasyMock.expectLastCall().once();

    EasyMock.replay(service);
    eventListener.onThriftServerStarted(address, port);
    EasyMock.verify(service);
  }
}
