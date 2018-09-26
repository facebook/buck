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

package com.facebook.buck.support.bgtasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

public class AsyncBackgroundTaskManagerTest {

  private AsyncBackgroundTaskManager manager;
  // tests should pass with arbitrary assignments (within reason) to these values
  private static final int NTHREADS = 3;
  private static final int FIRST_COMMAND_TASKS = 5;
  private static final int SECOND_COMMAND_TASKS = 4;
  private static final long TIMEOUT_MILLIS = 1000;

  @After
  public void tearDown() throws InterruptedException {
    manager.shutdown(5, TimeUnit.SECONDS);
  }

  private ImmutableList<BackgroundTask<TestArgs>> generateWaitingTaskList(
      int nTasks,
      boolean success,
      @Nullable CountDownLatch taskBlocker,
      @Nullable CountDownLatch taskWaiter,
      String name) {
    ImmutableList.Builder<BackgroundTask<TestArgs>> taskList = ImmutableList.builder();
    for (int i = 0; i < nTasks; i++) {
      BackgroundTask<TestArgs> task =
          ImmutableBackgroundTask.<TestArgs>builder()
              .setAction(new TestAction())
              .setActionArgs(new TestArgs(success, false, taskBlocker, taskWaiter, null))
              .setName(name)
              .build();
      taskList.add(task);
    }
    return taskList.build();
  }

  private ImmutableList<BackgroundTask<TestArgs>> generateNoWaitingTaskList(
      int nTasks, boolean success, String name) {
    return generateWaitingTaskList(nTasks, success, null, null, name);
  }

  private void assertOutputValuesEqual(String expected, List<BackgroundTask<TestArgs>> taskList) {
    for (BackgroundTask<TestArgs> task : taskList) {
      assertEquals(expected, task.getActionArgs().getOutput());
    }
  }

  @Test
  public void testScheduleCreatesManagedTask() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false))
            .setName("testTask")
            .build();
    schedule(task);
    assertEquals(task, manager.getScheduledTasks().peek().getTask());
  }

  @Test
  public void testBlockingSuccessPath() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, true, "successTask");
    schedule(taskList);
    manager.notify(Notification.COMMAND_END);

    assertOutputValuesEqual("succeeded", taskList);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testNonInterruptException() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, false, "failureTask");
    schedule(taskList);
    manager.notify(Notification.COMMAND_END);
    assertOutputValuesEqual("init", taskList);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testTaskInterruptBlocking() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(true, 1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, true))
            .setName("interruptTask")
            .build();
    schedule(task);
    manager.notify(Notification.COMMAND_END);
    assertFalse(manager.isShutDown());
    assertEquals("init", task.getActionArgs().getOutput());
    manager.shutdown(5, TimeUnit.SECONDS);
    assertTrue(manager.isShutDown());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testBlockingTimeout() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    CountDownLatch blocker = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(true, false, blocker, null, null))
            .setAction(new TestAction())
            .setName("timeoutTask")
            .setTimeout(Optional.of(Timeout.of(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)))
            .build();
    manager.notify(Notification.COMMAND_START);
    schedule(task);
    manager.notify(Notification.COMMAND_END);
    assertEquals("init", task.getActionArgs().getOutput());
    assertFalse(manager.isShutDown());
  }

  @Test
  public void testNonblockingNotify() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch taskBlocker = new CountDownLatch(1);
    CountDownLatch taskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateWaitingTaskList(FIRST_COMMAND_TASKS, true, taskBlocker, taskWaiter, "testTask");
    schedule(taskList);
    manager.notify(Notification.COMMAND_END);
    // all tasks should currently be waiting on taskBlocker(1)
    assertOutputValuesEqual("init", taskList);
    taskBlocker.countDown(); // signal tasks
    taskWaiter.await();
    assertOutputValuesEqual("succeeded", taskList);
  }

  @Test
  public void testNonblockingNewCommandNonOverlapping() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstTaskBlocker = new CountDownLatch(1);
    CountDownLatch firstTaskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);
    CountDownLatch secondTaskBlocker = new CountDownLatch(1);
    CountDownLatch secondTaskWaiter = new CountDownLatch(SECOND_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> firstCommandTasks =
        generateWaitingTaskList(
            FIRST_COMMAND_TASKS, true, firstTaskBlocker, firstTaskWaiter, "testTask");
    ImmutableList<BackgroundTask<TestArgs>> secondCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS, true, secondTaskBlocker, secondTaskWaiter, "nonOverlappingTask");

    manager.notify(Notification.COMMAND_START);
    schedule(firstCommandTasks);
    manager.notify(Notification.COMMAND_END);
    assertOutputValuesEqual("init", firstCommandTasks);
    firstTaskBlocker.countDown();
    firstTaskWaiter.await();
    assertOutputValuesEqual("succeeded", firstCommandTasks);
    assertEquals(0, manager.getScheduledTasks().size());

    manager.notify(Notification.COMMAND_START);
    schedule(secondCommandTasks);
    assertEquals(SECOND_COMMAND_TASKS, manager.getScheduledTasks().size());
    manager.notify(Notification.COMMAND_END);
    assertOutputValuesEqual("init", secondCommandTasks);
    secondTaskBlocker.countDown();
    secondTaskWaiter.await();
    assertOutputValuesEqual("succeeded", secondCommandTasks);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testNonblockingNewCommandsOverlapping() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstBlockingTaskBlocker = new CountDownLatch(1);

    CountDownLatch firstTaskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);
    CountDownLatch laterTasksWaiter = new CountDownLatch(SECOND_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> firstBlockingCommandTasks =
        generateWaitingTaskList(NTHREADS, true, firstBlockingTaskBlocker, null, "blockedTestTask");
    ImmutableList<BackgroundTask<TestArgs>> firstCommandTasks =
        generateWaitingTaskList(FIRST_COMMAND_TASKS, true, null, firstTaskWaiter, "testTask");
    ImmutableList<BackgroundTask<TestArgs>> secondCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS / 2, true, null, laterTasksWaiter, "secondCommandTask");
    ImmutableList<BackgroundTask<TestArgs>> thirdCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS - (SECOND_COMMAND_TASKS / 2),
            true,
            null,
            laterTasksWaiter,
            "thirdCommandTask");

    manager.notify(Notification.COMMAND_START);
    schedule(firstBlockingCommandTasks);
    schedule(firstCommandTasks);

    manager.notify(Notification.COMMAND_END); // the first set of tasks will be allowed to ran

    manager.notify(Notification.COMMAND_START);

    // allow the first set of tasks to complete to verify that new commands block more tasks from
    // completing.
    firstBlockingTaskBlocker.countDown();

    schedule(secondCommandTasks);
    manager.notify(Notification.COMMAND_START);
    schedule(thirdCommandTasks);
    // signal once -- this should not permit tasks to be run as one command is still waiting
    manager.notify(Notification.COMMAND_END);
    assertThat(
        manager.getScheduledTasks().size(),
        Matchers.greaterThanOrEqualTo(FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS));
    assertThat(
        manager.getScheduledTasks().size(),
        Matchers.lessThanOrEqualTo(FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS + NTHREADS));
    manager.notify(Notification.COMMAND_END); // actually permit to run
    firstTaskWaiter.await();
    laterTasksWaiter.await();
    assertOutputValuesEqual("succeeded", firstCommandTasks);
    assertOutputValuesEqual("succeeded", secondCommandTasks);
    assertOutputValuesEqual("succeeded", thirdCommandTasks);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testTaskInterruptNonblocking() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, 1);
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, true, blocker, waiter, null))
            .setName("interruptTask")
            .build();
    schedule(task);
    manager.notify(Notification.COMMAND_END);
    blocker.countDown();
    waiter.await(1, TimeUnit.SECONDS);
    assertFalse(manager.isShutDown());
    manager.shutdown(5, TimeUnit.SECONDS);
  }

  @Test
  public void testHardShutdownNonblocking() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    CountDownLatch taskStarted = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false, blocker, waiter, taskStarted))
            .setName("task")
            .build();
    manager.notify(Notification.COMMAND_START);
    schedule(task);
    manager.notify(Notification.COMMAND_END);
    taskStarted.await();
    manager.shutdownNow();
    blocker.countDown();
    waiter.await(1, TimeUnit.SECONDS);
    assertEquals(0, manager.getScheduledTasks().size());
    assertEquals("init", task.getActionArgs().getOutput());
    assertTrue(manager.isShutDown());
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false))
            .setName("noRunTask")
            .build();
    schedule(secondTask);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testSoftShutdownNonblocking() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    CountDownLatch taskStarted = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false, blocker, waiter, taskStarted))
            .setName("task")
            .build();
    manager.notify(Notification.COMMAND_START);
    schedule(task);
    manager.notify(Notification.COMMAND_END);
    taskStarted.await();
    blocker.countDown();
    manager.shutdown(5, TimeUnit.SECONDS);
    waiter.await();
    assertEquals("succeeded", task.getActionArgs().getOutput());
    assertTrue(manager.isShutDown());
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false))
            .setName("noRunTask")
            .build();
    schedule(secondTask);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testNonblockingTimeout() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstBlocker = new CountDownLatch(1);
    CountDownLatch secondBlocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(2);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(true, false, firstBlocker, waiter, null))
            .setAction(new TestAction())
            .setName("timeoutTask")
            .setTimeout(Optional.of(Timeout.of(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)))
            .build();
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(true, false, secondBlocker, waiter, null))
            .setAction(new TestAction())
            .setName("secondTask")
            .build();
    manager.notify(Notification.COMMAND_START);
    schedule(task);
    schedule(secondTask);
    manager.notify(Notification.COMMAND_END);
    secondBlocker.countDown();
    waiter.await(2 * TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals("init", task.getActionArgs().getOutput());
    assertEquals("succeeded", secondTask.getActionArgs().getOutput());
    assertFalse(manager.isShutDown());
  }

  @Test
  public void testCancellableTasks() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(2);
    CountDownLatch started = new CountDownLatch(1);
    BackgroundTask<TestArgs> firstTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false, blocker, waiter, null))
            .setName("cancelled")
            .setShouldCancelOnRepeat(true)
            .build();
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false, blocker, waiter, started))
            .setName("cancellable but runs")
            .setShouldCancelOnRepeat(true)
            .build();
    BackgroundTask<TestArgs> thirdTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false, blocker, waiter, null))
            .setName("thirdTask")
            .build();
    schedule(firstTask);
    schedule(secondTask);
    manager.notify(Notification.COMMAND_END);
    started.await();
    schedule(thirdTask);
    blocker.countDown();
    waiter.await();
    assertEquals("init", firstTask.getActionArgs().output);
    assertEquals("succeeded", secondTask.getActionArgs().output);
    assertEquals("succeeded", thirdTask.getActionArgs().output);
    assertEquals(0, manager.getScheduledTasks().size());
    assertEquals(0, manager.getCancellableTasks().size());
  }

  private void schedule(ImmutableList<? extends BackgroundTask<?>> taskList) {
    for (BackgroundTask<?> task : taskList) {
      schedule(task);
    }
  }

  private void schedule(BackgroundTask<?> task) {
    ManagedBackgroundTask managedTask = new ManagedBackgroundTask(task, new BuildId("TESTID"));
    manager.schedule(managedTask);
  }

  /**
   * Action that waits on a {@link CountDownLatch} before executing and that notifies another {@link
   * CountDownLatch} after execution, used to test execution of multiple async tasks.
   */
  static class TestAction implements TaskAction<TestArgs> {

    @Override
    public void run(TestArgs args) throws Exception {
      args.getTaskStarted().ifPresent(CountDownLatch::countDown);

      if (args.getTaskBlocker().isPresent()) {
        args.getTaskBlocker().get().await();
      }
      if (args.getInterrupt()) {
        throw new InterruptedException("TestAction task interrupted");
      }
      if (args.getSuccess()) {
        args.setOutput("succeeded");
        args.getTaskWaiter().ifPresent(CountDownLatch::countDown);
      } else {
        args.getTaskWaiter().ifPresent(CountDownLatch::countDown);
        throw new Exception("failed");
      }
    }
  }

  static class TestArgs {
    private boolean success;
    private String output;
    private final @Nullable CountDownLatch taskBlocker;
    private final @Nullable CountDownLatch taskWaiter;
    private final @Nullable CountDownLatch taskStarted;
    private final boolean interrupt;

    public TestArgs(
        boolean success,
        boolean interrupt,
        @Nullable CountDownLatch taskBlocker,
        @Nullable CountDownLatch taskWaiter,
        @Nullable CountDownLatch taskStarted) {
      this.success = success;
      this.output = "init";
      this.taskBlocker = taskBlocker;
      this.taskWaiter = taskWaiter;
      this.interrupt = interrupt;
      this.taskStarted = taskStarted;
    }

    public TestArgs(boolean success, boolean interrupt) {
      this(success, interrupt, null, null, null);
    }

    public boolean getSuccess() {
      return success;
    }

    public boolean getInterrupt() {
      return interrupt;
    }

    public String getOutput() {
      return output;
    }

    public void setOutput(String newOutput) {
      output = newOutput;
    }

    public Optional<CountDownLatch> getTaskBlocker() {
      return Optional.ofNullable(taskBlocker);
    }

    public Optional<CountDownLatch> getTaskWaiter() {
      return Optional.ofNullable(taskWaiter);
    }

    public Optional<CountDownLatch> getTaskStarted() {
      return Optional.ofNullable(taskStarted);
    }
  }
}
