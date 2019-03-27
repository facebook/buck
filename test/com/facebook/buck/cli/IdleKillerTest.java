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

package com.facebook.buck.cli;

import com.facebook.buck.testutil.AnnotatedRunnable;
import com.facebook.buck.testutil.FakeExecutor;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class IdleKillerTest {

  private final FakeExecutor fakeExecutor = new FakeExecutor();

  @Test
  public void schedulesKillTaskAfterDelay() {
    IdleKiller idleKiller = new IdleKiller(fakeExecutor, Duration.ofMinutes(1), () -> {});
    idleKiller.setIdleKillTask();
    Optional<AnnotatedRunnable> item = fakeExecutor.getRunnableList().stream().findFirst();
    Assert.assertTrue(item.isPresent());
    Assert.assertEquals("kill task should not repeat", -1, item.get().getDelay());
    Assert.assertEquals(
        "kill task should have correct delay",
        Duration.ofMinutes(1),
        Duration.ofMinutes(item.get().getUnit().toMinutes(item.get().getInitDelay())));
  }

  @Test
  public void cancelScheduledTaskWhenClearTaskIsCalled() {
    IdleKiller idleKiller = new IdleKiller(fakeExecutor, Duration.ofMinutes(1), () -> {});
    idleKiller.setIdleKillTask();
    idleKiller.clearIdleKillTask(); // Before closing the second task...

    List<AnnotatedRunnable> scheduledRunnables = fakeExecutor.getRunnableList();
    Assert.assertEquals("Should have scheduled one task", 1, scheduledRunnables.size());
    Assert.assertTrue(
        "That task should be cancelled", scheduledRunnables.get(0).getFuture().isCancelled());
  }
}
