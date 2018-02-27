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

import com.facebook.buck.distributed.NoopArtifactCacheByBuildRule;
import com.facebook.buck.distributed.testutil.CustomBuildRuleResolverFactory;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.listener.NoOpCoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleDepsCache;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MinionWorkloadAllocatorTest {

  private static final String MINION_ONE = "Super minion 1";
  private static final int MAX_WORK_UNITS = 10;
  public static final int MOST_BUILD_RULES_FINISHED_PERCENTAGE = 100;

  private BuildTargetsQueue queue;
  private BuildTarget target;

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = CustomBuildRuleResolverFactory.createDiamondDependencyResolver();
    target = BuildTargetFactory.newInstance(CustomBuildRuleResolverFactory.ROOT_TARGET);
    queue =
        new CacheOptimizedBuildTargetsQueueFactory(
                resolver, new NoopArtifactCacheByBuildRule(), false, new RuleDepsCache(resolver))
            .createBuildTargetsQueue(
                ImmutableList.of(target),
                new NoOpCoordinatorBuildRuleEventsPublisher(),
                MOST_BUILD_RULES_FINISHED_PERCENTAGE);
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
            MINION_ONE,
            ImmutableList.of(CustomBuildRuleResolverFactory.LEAF_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(2, secondTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> thirdTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(
                CustomBuildRuleResolverFactory.LEFT_TARGET,
                CustomBuildRuleResolverFactory.RIGHT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(1, thirdTargets.size());
    Assert.assertFalse(allocator.isBuildFinished());

    List<WorkUnit> fourthTargets =
        allocator.dequeueZeroDependencyNodes(
            MINION_ONE,
            ImmutableList.of(CustomBuildRuleResolverFactory.ROOT_TARGET),
            MAX_WORK_UNITS);
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }
}
