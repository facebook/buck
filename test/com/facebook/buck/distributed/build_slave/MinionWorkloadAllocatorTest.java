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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MinionWorkloadAllocatorTest {

  private static final String MINION_ONE = "Super minion 1";
  private static final int MAX_WORK_UNITS = 10;

  private BuildTargetsQueue queue;
  private BuildTarget target;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = BuildTargetsQueueTest.createDiamondDependencyResolver();
    target = BuildTargetFactory.newInstance(BuildTargetsQueueTest.TARGET_NAME);
    queue = BuildTargetsQueue.newQueue(resolver, ImmutableList.of(target));
  }

  @Test
  public void testNormalBuildFlow() {
    MinionWorkloadAllocator allocator = new MinionWorkloadAllocator(queue);
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> firstTargets =
        allocator.dequeueZeroDependencyNodes(MINION_ONE, ImmutableList.of(), MAX_WORK_UNITS);
    Assert.assertEquals(1, firstTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> secondTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE, ImmutableList.of(BuildTargetsQueueTest.LEAF_TARGET), MAX_WORK_UNITS);
    Assert.assertEquals(2, secondTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> thirdTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(BuildTargetsQueueTest.LEFT_TARGET, BuildTargetsQueueTest.RIGHT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(1, thirdTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> fourthTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE, ImmutableList.of(BuildTargetsQueueTest.TARGET_NAME), MAX_WORK_UNITS);
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }
}
