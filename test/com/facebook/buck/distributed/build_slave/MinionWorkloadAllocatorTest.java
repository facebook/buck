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

import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.keys.RuleKeyConfiguration;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Optional;
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
    target = BuildTargetFactory.newInstance(BuildTargetsQueueTest.ROOT_TARGET);
    queue =
        new BuildTargetsQueueFactory(
                resolver,
                MoreExecutors.newDirectExecutorService(),
                false,
                new NoopArtifactCache(),
                new DefaultBuckEventBus(FakeClock.DO_NOT_CARE, new BuildId()),
                new DummyFileHashCache(),
                RuleKeyConfiguration.builder()
                    .setCoreKey("dummy")
                    .setSeed(0)
                    .setBuildInputRuleKeyFileSizeLimit(100)
                    .build(),
                Optional.empty())
            .newQueue(ImmutableList.of(target));
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
            MINION_ONE, ImmutableList.of(BuildTargetsQueueTest.ROOT_TARGET), MAX_WORK_UNITS);
    Assert.assertEquals(0, fourthTargets.size());
    Assert.assertTrue(allocator.isBuildFinished());
  }
}
