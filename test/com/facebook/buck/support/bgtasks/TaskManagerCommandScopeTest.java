/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.bgtasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.facebook.buck.util.types.Unit;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.junit.Test;

public class TaskManagerCommandScopeTest {

  @Test
  public void taskManagerDoesNotReportCommandEndTwiceOnMultipleClose() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), false);

    testBackgroundTaskManager.notify(Notification.COMMAND_START); // one extra command

    scope.close();
    scope.close(); // two closes should not send two command ends

    assertEquals(1, testBackgroundTaskManager.getCommandsRunning());
  }

  @Test
  public void taskManagerCommandScopeSchedulesTasksToManager() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), true);
    BackgroundTask<?> task = BackgroundTask.of("test", ignored -> fail(), new Object());
    scope.schedule(task);

    assertSame(
        task, Iterables.getOnlyElement(testBackgroundTaskManager.getScheduledTasks()).getTask());
  }

  @Test
  public void taskManagerCommandScopeStoresAllTasksForScope() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), true);
    BackgroundTask<?> task1 = BackgroundTask.of("test1", ignored -> fail(), new Object());
    BackgroundTask<?> task2 = BackgroundTask.of("test2", ignored -> {}, new Object());
    scope.schedule(task1);
    scope.schedule(task2);

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks = scope.getScheduledTasksResults();

    assertTrue(scheduledTasks.containsKey(task1));
    assertTrue(scheduledTasks.containsKey(task2));

    assertFalse(scheduledTasks.get(task1).isDone());
    assertFalse(scheduledTasks.get(task2).isDone());
  }

  @Test
  public void taskManagerRejectsTasksAfterClose() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), true);
    scope.close();
    BackgroundTask<?> task = BackgroundTask.of("test1", ignored -> fail(), new Object());
    scope.schedule(task);

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks = scope.getScheduledTasksResults();

    assertFalse(scheduledTasks.containsKey(task));
  }

  @Test
  public void commandScopeCloseBlocksUntilTasksCompleteForBlockingMode() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), true);
    BackgroundTask<?> task1 = BackgroundTask.of("test1", ignored -> {}, new Object());
    BackgroundTask<?> task2 = BackgroundTask.of("test2", ignored -> {}, new Object());
    scope.schedule(task1);
    scope.schedule(task2);

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks = scope.getScheduledTasksResults();

    assertTrue(scheduledTasks.containsKey(task1));
    assertTrue(scheduledTasks.containsKey(task2));

    scope.close();

    for (Future<Unit> f : scheduledTasks.values()) {
      assertTrue(f.isDone());
    }
  }

  @Test
  public void commandScopeCloseBlocksOnlyForTasksItScheduled() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope1 =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), true);
    TaskManagerCommandScope scope2 =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), false);

    Semaphore semaphore = new Semaphore(0);

    BackgroundTask<?> task1 = BackgroundTask.of("test1", ignored -> {}, new Object());
    BackgroundTask<?> task2 =
        BackgroundTask.of("test2", ignored -> semaphore.acquire(), new Object());
    scope1.schedule(task1);
    scope2.schedule(task2);

    scope2.close();
    scope1.close();

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks1 =
        scope1.getScheduledTasksResults();

    assertTrue(scheduledTasks1.containsKey(task1));
    assertTrue(scheduledTasks1.get(task1).isDone());

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks2 =
        scope2.getScheduledTasksResults();

    assertTrue(scheduledTasks2.containsKey(task2));
    assertFalse(scheduledTasks2.get(task2).isDone());
  }

  @Test
  public void commandScopeCloseDoesNotBlockForAsyncMode() {
    TestBackgroundTaskManager testBackgroundTaskManager = TestBackgroundTaskManager.of();
    TaskManagerCommandScope scope =
        new TaskManagerCommandScope(testBackgroundTaskManager, new BuildId(), false);

    Semaphore blocker = new Semaphore(0);

    BackgroundTask<?> task1 =
        BackgroundTask.of("test1", ignored -> blocker.acquire(), new Object());
    BackgroundTask<?> task2 =
        BackgroundTask.of("test2", ignored -> blocker.acquire(), new Object());
    scope.schedule(task1);
    scope.schedule(task2);

    ImmutableMap<BackgroundTask<?>, Future<Unit>> scheduledTasks = scope.getScheduledTasksResults();

    assertTrue(scheduledTasks.containsKey(task1));
    assertTrue(scheduledTasks.containsKey(task2));

    scope.close();

    for (Future<Unit> f : scheduledTasks.values()) {
      assertFalse(f.isDone());
    }
  }
}
