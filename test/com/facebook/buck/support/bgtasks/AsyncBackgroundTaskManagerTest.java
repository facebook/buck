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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager.Notification;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AsyncBackgroundTaskManagerTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

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
      Optional<Exception> failure,
      @Nullable CountDownLatch taskBlocker,
      @Nullable CountDownLatch taskWaiter,
      String name) {
    ImmutableList.Builder<BackgroundTask<TestArgs>> taskList = ImmutableList.builder();
    for (int i = 0; i < nTasks; i++) {
      BackgroundTask<TestArgs> task =
          ImmutableBackgroundTask.<TestArgs>builder()
              .setAction(new TestAction())
              .setActionArgs(new TestArgs(failure, taskBlocker, taskWaiter, null))
              .setName(name)
              .build();
      taskList.add(task);
    }
    return taskList.build();
  }

  private ImmutableList<BackgroundTask<TestArgs>> generateNoWaitingTaskList(
      int nTasks, Optional<Exception> failure, String name) {
    return generateWaitingTaskList(nTasks, failure, null, null, name);
  }

  @Test
  public void testBlockingSuccessPath() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, Optional.empty(), "successTask");
    ImmutableList<Future<Void>> futures = schedule(taskList);
    manager.notify(Notification.COMMAND_END);

    for (Future<?> f : futures) {
      assertTrue(f.isDone());
    }
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testNonInterruptException() {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    Exception expectedException = new Exception();
    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateNoWaitingTaskList(1, Optional.of(expectedException), "failureTask");
    ImmutableList<Future<Void>> futures = schedule(taskList);
    manager.notify(Notification.COMMAND_END);

    for (Future<?> f : futures) {
      assertTrue(f.isDone());
      try {
        f.get();
        fail("Expected an Exception from the task");
      } catch (InterruptedException e) {
        fail("Expected an Exception from the task");
      } catch (ExecutionException e) {
        assertSame(expectedException, e.getCause());
      }
    }

    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testTaskInterruptBlocking() throws InterruptedException {
    manager = new AsyncBackgroundTaskManager(true, 1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty(), new CountDownLatch(1), null, null))
            .setName("interruptTask")
            .build();
    Future<Void> future = schedule(task);
    manager.shutdown(5, TimeUnit.SECONDS);
    manager.notify(Notification.COMMAND_END);
    assertFalse(future.isCancelled());
    assertFalse(future.isDone());
    assertTrue(manager.isShutDown());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testBlockingTimeout() throws ExecutionException, InterruptedException {
    manager = new AsyncBackgroundTaskManager(true, NTHREADS);
    CountDownLatch blocker = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(Optional.empty(), blocker, null, null))
            .setAction(new TestAction())
            .setName("timeoutTask")
            .setTimeout(Optional.of(Timeout.of(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)))
            .build();
    manager.notify(Notification.COMMAND_START);
    Future<Void> future = schedule(task);
    manager.notify(Notification.COMMAND_END);

    expectedException.expect(CancellationException.class);
    future.get();
  }

  @Test
  public void testNonblockingNotify() {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch taskBlocker = new CountDownLatch(1);
    CountDownLatch taskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> taskList =
        generateWaitingTaskList(
            FIRST_COMMAND_TASKS, Optional.empty(), taskBlocker, taskWaiter, "testTask");
    ImmutableList<Future<Void>> futures = schedule(taskList);
    manager.notify(Notification.COMMAND_END);
    // all tasks should currently be waiting on taskBlocker(1)

    assertFuturesNotDone(futures);
    taskBlocker.countDown(); // signal tasks
    assertFuturesSuccessful(futures);
  }

  @Test
  public void testNonblockingNewCommandNonOverlapping() {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstTaskBlocker = new CountDownLatch(1);
    CountDownLatch firstTaskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);
    CountDownLatch secondTaskBlocker = new CountDownLatch(1);
    CountDownLatch secondTaskWaiter = new CountDownLatch(SECOND_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> firstCommandTasks =
        generateWaitingTaskList(
            FIRST_COMMAND_TASKS, Optional.empty(), firstTaskBlocker, firstTaskWaiter, "testTask");
    ImmutableList<BackgroundTask<TestArgs>> secondCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS,
            Optional.empty(),
            secondTaskBlocker,
            secondTaskWaiter,
            "nonOverlappingTask");

    manager.notify(Notification.COMMAND_START);
    ImmutableList<Future<Void>> firstCommandFutures = schedule(firstCommandTasks);
    manager.notify(Notification.COMMAND_END);
    assertFuturesNotDone(firstCommandFutures);

    firstTaskBlocker.countDown();
    assertFuturesSuccessful(firstCommandFutures);

    assertEquals(0, manager.getScheduledTasks().size());

    manager.notify(Notification.COMMAND_START);
    ImmutableList<Future<Void>> secondCommandFutures = schedule(secondCommandTasks);
    assertEquals(SECOND_COMMAND_TASKS, manager.getScheduledTasks().size());
    manager.notify(Notification.COMMAND_END);
    assertFuturesNotDone(secondCommandFutures);

    secondTaskBlocker.countDown();
    assertFuturesSuccessful(secondCommandFutures);
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testNonblockingNewCommandsOverlapping() {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstBlockingTaskBlocker = new CountDownLatch(1);

    CountDownLatch firstTaskWaiter = new CountDownLatch(FIRST_COMMAND_TASKS);
    CountDownLatch laterTasksWaiter = new CountDownLatch(SECOND_COMMAND_TASKS);

    ImmutableList<BackgroundTask<TestArgs>> firstBlockingCommandTasks =
        generateWaitingTaskList(
            NTHREADS, Optional.empty(), firstBlockingTaskBlocker, null, "blockedTestTask");
    ImmutableList<BackgroundTask<TestArgs>> firstCommandTasks =
        generateWaitingTaskList(
            FIRST_COMMAND_TASKS, Optional.empty(), null, firstTaskWaiter, "testTask");
    ImmutableList<BackgroundTask<TestArgs>> secondCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS / 2,
            Optional.empty(),
            null,
            laterTasksWaiter,
            "secondCommandTask");
    ImmutableList<BackgroundTask<TestArgs>> thirdCommandTasks =
        generateWaitingTaskList(
            SECOND_COMMAND_TASKS - (SECOND_COMMAND_TASKS / 2),
            Optional.empty(),
            null,
            laterTasksWaiter,
            "thirdCommandTask");

    manager.notify(Notification.COMMAND_START);
    ImmutableList<Future<Void>> firstCommandBlockingFutures = schedule(firstBlockingCommandTasks);
    ImmutableList<Future<Void>> firstCommandFutures = schedule(firstCommandTasks);

    manager.notify(Notification.COMMAND_END); // the first set of tasks will be allowed to ran

    manager.notify(Notification.COMMAND_START);

    // allow the first set of tasks to complete to verify that new commands block more tasks from
    // completing.
    firstBlockingTaskBlocker.countDown();

    ImmutableList<Future<Void>> secondCommandFutures = schedule(secondCommandTasks);
    manager.notify(Notification.COMMAND_START);
    ImmutableList<Future<Void>> thirdCommandFutures = schedule(thirdCommandTasks);
    // signal once -- this should not permit tasks to be run as one command is still waiting
    manager.notify(Notification.COMMAND_END);
    assertThat(
        manager.getScheduledTasks().size(),
        Matchers.greaterThanOrEqualTo(FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS));
    assertThat(
        manager.getScheduledTasks().size(),
        Matchers.lessThanOrEqualTo(FIRST_COMMAND_TASKS + SECOND_COMMAND_TASKS + NTHREADS));
    manager.notify(Notification.COMMAND_END); // actually permit to run

    assertFuturesSuccessful(firstCommandBlockingFutures);
    assertFuturesSuccessful(firstCommandFutures);
    assertFuturesSuccessful(secondCommandFutures);
    assertFuturesSuccessful(thirdCommandFutures);

    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test
  public void testHardShutdownNonblocking() throws InterruptedException, ExecutionException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    CountDownLatch taskStarted = new CountDownLatch(1);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty(), blocker, waiter, taskStarted))
            .setName("task")
            .build();
    manager.notify(Notification.COMMAND_START);
    Future<Void> taskFuture = schedule(task);
    manager.notify(Notification.COMMAND_END);
    taskStarted.await();
    manager.shutdownNow();
    blocker.countDown();
    assertEquals(0, manager.getScheduledTasks().size());

    assertFutureCancelled(taskFuture);
    assertTrue(manager.isShutDown());

    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty()))
            .setName("noRunTask")
            .build();
    Future<Void> secondFuture = schedule(secondTask);
    assertEquals(0, manager.getScheduledTasks().size());
    assertTrue(secondFuture.isCancelled());
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
            .setActionArgs(new TestArgs(Optional.empty(), blocker, waiter, taskStarted))
            .setName("task")
            .build();
    manager.notify(Notification.COMMAND_START);
    Future<Void> firstTaskFuture = schedule(task);
    manager.notify(Notification.COMMAND_END);
    taskStarted.await();
    manager.shutdown(5, TimeUnit.SECONDS);
    blocker.countDown();
    waiter.await();
    assertFutureSuccessful(firstTaskFuture);
    assertTrue(manager.isShutDown());

    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty()))
            .setName("noRunTask")
            .build();
    Future<Void> secondTaskFuture = schedule(secondTask);
    assertTrue(secondTaskFuture.isCancelled());
    assertEquals(0, manager.getScheduledTasks().size());
  }

  @Test(timeout = 8 * TIMEOUT_MILLIS)
  public void testNonblockingTimeout() throws InterruptedException, ExecutionException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch firstBlocker = new CountDownLatch(1);
    CountDownLatch secondBlocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(2);
    BackgroundTask<TestArgs> task =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(Optional.empty(), firstBlocker, waiter, null))
            .setAction(new TestAction())
            .setName("timeoutTask")
            .setTimeout(Optional.of(Timeout.of(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)))
            .build();
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setActionArgs(new TestArgs(Optional.empty(), secondBlocker, waiter, null))
            .setAction(new TestAction())
            .setName("secondTask")
            .build();
    manager.notify(Notification.COMMAND_START);
    Future<Void> firstTaskFuture = schedule(task);
    Future<Void> secondTaskFuture = schedule(secondTask);
    manager.notify(Notification.COMMAND_END);
    secondBlocker.countDown();
    waiter.await(2 * TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertFutureCancelled(firstTaskFuture);
    assertFutureSuccessful(secondTaskFuture);
    assertFalse(manager.isShutDown());
  }

  @Test
  public void testCancellableTasks() throws InterruptedException, ExecutionException {
    manager = new AsyncBackgroundTaskManager(false, NTHREADS);

    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(2);
    CountDownLatch started = new CountDownLatch(1);
    BackgroundTask<TestArgs> firstTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty(), blocker, waiter, null))
            .setName("cancelled")
            .setShouldCancelOnRepeat(true)
            .build();
    BackgroundTask<TestArgs> secondTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty(), blocker, waiter, started))
            .setName("cancellable but runs")
            .setShouldCancelOnRepeat(true)
            .build();
    BackgroundTask<TestArgs> thirdTask =
        ImmutableBackgroundTask.<TestArgs>builder()
            .setAction(new TestAction())
            .setActionArgs(new TestArgs(Optional.empty(), blocker, waiter, null))
            .setName("thirdTask")
            .build();
    Future<Void> firstTaskFuture = schedule(firstTask);
    Future<Void> secondTaskFuture = schedule(secondTask);
    manager.notify(Notification.COMMAND_END);
    started.await();
    Future<Void> thirdTaskFuture = schedule(thirdTask);
    blocker.countDown();

    assertFutureSuccessful(secondTaskFuture);
    assertFutureSuccessful(thirdTaskFuture);
    assertFutureCancelled(firstTaskFuture);

    assertEquals(0, manager.getScheduledTasks().size());
    assertEquals(0, manager.getCancellableTasks().size());
  }

  private ImmutableList<Future<Void>> schedule(
      ImmutableList<? extends BackgroundTask<?>> taskList) {
    return ImmutableList.copyOf(Lists.transform(taskList, this::schedule));
  }

  private Future<Void> schedule(BackgroundTask<?> task) {
    ManagedBackgroundTask managedTask = new ManagedBackgroundTask(task, new BuildId("TESTID"));
    return manager.schedule(managedTask);
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
      if (args.getFailure().isPresent()) {
        args.getTaskWaiter().ifPresent(CountDownLatch::countDown);
        throw args.getFailure().get();
      } else {
        args.getTaskWaiter().ifPresent(CountDownLatch::countDown);
      }
    }
  }

  static class TestArgs {
    private Optional<Exception> failure;
    private final @Nullable CountDownLatch taskBlocker;
    private final @Nullable CountDownLatch taskWaiter;
    private final @Nullable CountDownLatch taskStarted;

    public TestArgs(
        Optional<Exception> failure,
        @Nullable CountDownLatch taskBlocker,
        @Nullable CountDownLatch taskWaiter,
        @Nullable CountDownLatch taskStarted) {
      this.failure = failure;
      this.taskBlocker = taskBlocker;
      this.taskWaiter = taskWaiter;
      this.taskStarted = taskStarted;
    }

    public TestArgs(Optional<Exception> failure) {
      this(failure, null, null, null);
    }

    public Optional<Exception> getFailure() {
      return failure;
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

  private void assertFuturesSuccessful(ImmutableList<Future<Void>> futures) {
    for (Future<Void> f : futures) {
      assertFutureSuccessful(f);
    }
  }

  private void assertFutureSuccessful(Future<Void> future) {
    try {
      future.get();
    } catch (Throwable e) {
      fail(String.format("Expected success, but got exception %s", e));
    }
  }

  private void assertFutureCancelled(Future<Void> future)
      throws ExecutionException, InterruptedException {
    try {
      future.get();
      fail("Expected future to be canceled, but got a result instead");
    } catch (CancellationException e) {
      // success
    }
  }

  private void assertFuturesNotDone(ImmutableList<Future<Void>> futures) {
    for (Future<Void> f : futures) {
      assertFalse(f.isDone());
    }
  }
}
