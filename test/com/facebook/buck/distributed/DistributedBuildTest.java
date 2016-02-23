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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;

import org.junit.Test;

public class DistributedBuildTest {

  @Test
  public void testExecuteAndPrintFailuresToEventBus() {
    BuckEventBus eventBus = createMock(BuckEventBus.class);
    eventBus.post(isA(DistBuildStatusEvent.class));
    expectLastCall().atLeastOnce(); // this is the only thing we're checking for now
    replay(eventBus);

    assertEquals(createDistBuild(eventBus).executeAndPrintFailuresToEventBus(), 0);
    verify(eventBus);
  }

  private DistributedBuild createDistBuild(BuckEventBus eventBus) {
    return new DistributedBuild(
        new DistBuildService(new DistBuildConfig(FakeBuckConfig.builder().build())),
        eventBus
    );
  }

}
