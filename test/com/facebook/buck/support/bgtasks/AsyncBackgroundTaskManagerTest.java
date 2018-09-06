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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AsyncBackgroundTaskManagerTest {

  private static final Logger LOG = Logger.get(AsyncBackgroundTaskManagerTest.class);

  private AsyncBackgroundTaskManager blockingManager;
  private AsyncBackgroundTaskManager nonblockingManager;
  // tests should pass with arbitrary assignments (within reason) to these values
  private static final int NTHREADS = 3;
  private static final int FIRST_COMMAND_TASKS = 5;
  private static final int SECOND_COMMAND_TASKS = 4;
  private static final long TIMEOUT_MILLIS = 1000;

  @Before
  public void setUp() {
    nonblockingManager = new AsyncBackgroundTaskManager(false, NTHREADS);
    blockingManager = new AsyncBackgroundTaskManager(true, NTHREADS);
  }

  @After
  public void tearDown() {
    try {
      nonblockingManager.shutdown(5, TimeUnit.SECONDS);
      blockingManager.shutdown(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      LOG.info("Shutdown interrupted");
    }
  }

  private ImmutableList<BackgroundTask<TestArgs>> generateWaitingTaskList(
      int nTasks,
      boolean success,
      CountDownLatch taskBlocker,
      CountDownLatch taskWaiter,
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

  /** Basic tests *************************************************************************** */
  @Test
  public void testScheduleCreatesManagedTask() {
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, false))
            .setName("testTask")
            .build();
    blockingManager.schedule(task);
    assertEquals(task, blockingManager.getScheduledTasks().peek().getTask());
  }

  /** Blocking mode tests ******************************************************************* */
  @Test
  public void testBlockingSuccessPath() {
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, true, "successTask");
    blockingManager.schedule(taskList);
    blockingManager.notify(Notification.COMMAND_END);

    assertOutputValuesEqual("succeeded", taskList);
    assertEquals(0, blockingManager.getScheduledTasks().size());
  }

  @Test
  public void testNonInterruptException() {
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, false, "failureTask");
    blockingManager.schedule(taskList);
    blockingManager.notify(Notification.COMMAND_END);
    assertOutputValuesEqual("init", taskList);
    assertEquals(0, blockingManager.getScheduledTasks().size());
  }

  @Test
  public void testTaskInterruptBlocking() throws InterruptedException {
    AsyncBackgroundTaskManager manager = new AsyncBackgroundTaskManager(true, 1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, true))
            .setName("interruptTask")
            .build();
    manager.schedule(task);
    manager.notify(Notification.COMMAND_END);
    assertFalse(manager.isShutDown());
    assertEquals("init", task.getActionArgs().getOutput());
    manager.shutdown(5, TimeUnit.SECONDS);
    assertTrue(manager.isShutDown());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testBlockingTimeout() {
    CountDownLatch blocker = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(true, false, blocker, null, null))
            .setAction(new TestAction())
            .setName("timeoutTask")
            .setTimeout(Optional.of(Timeout.of(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)))
            .build();
    blockingManager.notify(Notification.COMMAND_START);
    blockingManager.schedule(task);
    blockingManager.notify(Notification.COMMAND_END);
    assertEquals("init", task.getActionArgs().getOutput());
    assertFalse(blockingManager.isShutDown());
  }

  /** Nonblocking mode tests **************************************************************** */
  @Test
  public void testNonblockingNotify() throws InterruptedException {
    CountDownLatch taskBlocker = new CountDownLatch(1);
    CountDownLatch taskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateWaitingTaskList(FIRST_COMMAND_TASKS, true, taskBlocker, taskWaiter, "testTask");
    nonblockingManager.schedule(taskList);
    nonblockingManager.notify(Notification.COMMAND_END);
    // all tasks should currently be waiting on taskBlocker(1)
    assertOutputValuesEqual("init", taskList);
    taskBlocker.countDown(); // signal tasks
    taskWaiter.await();
    assertOutputValuesEqual("succeeded", taskList);
  }

  @Test
  public void testNonblockingNewCommandNonOverlapping() throws InterruptedException {
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

    nonblockingManager.notify(Notification.COMMAND_START); // start of first command
    nonblockingManager.schedule(firstCommandTasks);
    nonblockingManager.notify(Notification.COMMAND_END);
    // task1 in run, waiting on taskBlocker
    assertOutputValuesEqual("init", firstCommandTasks);
    firstTaskBlocker.countDown(); // enable all tasks in first command
    firstTaskWaiter.await();
    assertOutputValuesEqual("succeeded", firstCommandTasks);
    assertEquals(0, nonblockingManager.getScheduledTasks().size());

    nonblockingManager.notify(Notification.COMMAND_START); // new command comes in
    nonblockingManager.schedule(secondCommandTasks);
    assertEquals(SECOND_COMMAND_TASKS, nonblockingManager.getScheduledTasks().size());
    nonblockingManager.notify(Notification.COMMAND_END);
    assertOutputValuesEqual("init", secondCommandTasks);
    secondTaskBlocker.countDown();
    secondTaskWaiter.await();
    assertOutputValuesEqual("succeeded", secondCommandTasks);
    assertEquals(0, nonblockingManager.getScheduledTasks().size());
  }

  @Test
  public void testNonblockingNewCommandsOverlapping() throws InterruptedException {
    CountDownLatch firstTaskBlocker = new CountDownLatch(1);
    CountDownLatch firstTaskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);
    CountDownLatch laterTasksBlocker = new CountDownLatch(1);
    CountDownLatch laterTasksWaiter = new CountDownLatch(SECOND_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> firstCommandTasks =
        generateWaitingTaskList(
            FIRST_COMMAND_TASKS, true, firstTaskBlocker, firstTaskWaiter, "testTask");
    ImmutableList<BackgroundTask<TestArgs>> secondCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS / 2,
            true,
            laterTasksBlocker,
            laterTasksWaiter,
            "secondCommandTask");
    ImmutableList<BackgroundTask<TestArgs>> thirdCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS - (SECOND_COMMAND_TASKS / 2),
            true,
            laterTasksBlocker,
            laterTasksWaiter,
            "thirdCommandTask");

    nonblockingManager.notify(Notification.COMMAND_START); // start of first command
    nonblockingManager.schedule(firstCommandTasks);
    nonblockingManager.notify(Notification.COMMAND_END);
    firstTaskBlocker.countDown();

    nonblockingManager.notify(Notification.COMMAND_START); // second command comes in
    nonblockingManager.schedule(secondCommandTasks);
    nonblockingManager.notify(Notification.COMMAND_START); // third command comes in
    nonblockingManager.schedule(thirdCommandTasks);
    // signal once -- this should not permit tasks to be run as one command is still waiting
    nonblockingManager.notify(Notification.COMMAND_END);
    laterTasksBlocker.countDown();
    assertTrue(
        nonblockingManager.getScheduledTasks().size()
            >= FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS - 1);
    assertTrue(
        nonblockingManager.getScheduledTasks().size()
            <= FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS);
    nonblockingManager.notify(Notification.COMMAND_END); // actually permit to run
    firstTaskWaiter.await();
    laterTasksWaiter.await();
    assertOutputValuesEqual("succeeded", firstCommandTasks);
    assertOutputValuesEqual("succeeded", secondCommandTasks);
    assertOutputValuesEqual("succeeded", thirdCommandTasks);
    assertEquals(0, nonblockingManager.getScheduledTasks().size());
  }

  @Test
  public void testTaskInterruptNonblocking() throws InterruptedException {
    AsyncBackgroundTaskManager manager = new AsyncBackgroundTaskManager(false, 1);
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(true, true, blocker, waiter, null))
            .setName("interruptTask")
            .build();
    manager.schedule(task);
    manager.notify(Notification.COMMAND_END);
    blocker.countDown();
    waiter.await(1, TimeUnit.SECONDS); // since this waiter is never counted down
    assertFalse(manager.isShutDown());
    manager.shutdown(5, TimeUnit.SECONDS);
  }

  @Test
  public void testHardShutdownNonblocking() throws InterruptedException {
    AsyncBackgroundTaskManager manager = new AsyncBackgroundTaskManager(false, NTHREADS);
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
    manager.schedule(task);
    manager.notify(Notification.COMMAND_END);
    taskStarted.await();
    manager.shutdownNow();
    blocker.countDown(); // unblock tasks but manager should be already shut down
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
    manager.schedule(secondTask);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testSoftShutdownNonblocking() throws InterruptedException {
    AsyncBackgroundTaskManager manager = new AsyncBackgroundTaskManager(false, NTHREADS);
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
    manager.schedule(task);
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
    manager.schedule(secondTask);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testNonblockingTimeout() throws InterruptedException {
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
    nonblockingManager.notify(Notification.COMMAND_START);
    nonblockingManager.schedule(task);
    nonblockingManager.schedule(secondTask);
    nonblockingManager.notify(Notification.COMMAND_END);
    secondBlocker.countDown();
    waiter.await(2 * TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals("init", task.getActionArgs().getOutput());
    assertEquals("succeeded", secondTask.getActionArgs().getOutput());
    assertFalse(nonblockingManager.isShutDown());
  }

  /**
   * Action that waits on a {@link CountDownLatch} before executing and that notifies another {@link
   * CountDownLatch} after execution, used to test execution of multiple async tasks.
   */
  static class TestAction implements TaskAction<TestArgs> {

    @Override
    public void run(TestArgs args) throws Exception {
      if (args.getTaskStarted() != null) {
        args.getTaskStarted().countDown();
      }
      if (args.getTaskBlocker() != null) {
        args.getTaskBlocker().await();
      }
      if (args.getInterrupt()) {
        throw new InterruptedException("TestAction task interrupted");
      }
      if (args.getSuccess()) {
        args.setOutput("succeeded");
        if (args.getTaskWaiter() != null) {
          args.getTaskWaiter().countDown();
        }
      } else {
        if (args.getTaskWaiter() != null) {
          args.getTaskWaiter().countDown();
        }
        throw new Exception("failed");
      }
    }
  }

  static class TestArgs {
    private boolean success;
    private String output;
    private final CountDownLatch taskBlocker;
    private final CountDownLatch taskWaiter;
    private final CountDownLatch taskStarted;
    private final boolean interrupt;

    public TestArgs(
        boolean success,
        boolean interrupt,
        CountDownLatch taskBlocker,
        CountDownLatch taskWaiter,
        CountDownLatch taskStarted) {
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

    public CountDownLatch getTaskBlocker() {
      return taskBlocker;
    }

    public CountDownLatch getTaskWaiter() {
      return taskWaiter;
    }

    public CountDownLatch getTaskStarted() {
      return taskStarted;
    }
  }
}
