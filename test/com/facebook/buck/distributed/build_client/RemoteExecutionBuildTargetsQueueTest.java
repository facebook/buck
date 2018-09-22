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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.Lists;
import java.util.List;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionBuildTargetsQueueTest {

  private static final int MAX_WORK_UNITS = Integer.MAX_VALUE;

  private BuckEventBus buckEventBus;
  private RemoteExecutionBuildTargetsQueue queue;

  @Before
  public void setUp() {
    buckEventBus = EasyMock.createNiceMock(BuckEventBus.class);
    queue = new RemoteExecutionBuildTargetsQueue();
  }

  @Test
  public void testEnqueuingOneTarget() {
    String target = "super target";
    queue.enqueueForRemoteBuild(target);

    List<String> finishedNodes = Lists.newArrayList();
    List<WorkUnit> workUnits = queue.dequeueZeroDependencyNodes(finishedNodes, MAX_WORK_UNITS);
    Assert.assertEquals(1, workUnits.size());
    Assert.assertEquals(1, workUnits.get(0).getBuildTargetsSize());
    Assert.assertEquals(target, workUnits.get(0).getBuildTargets().get(0));
  }
}
