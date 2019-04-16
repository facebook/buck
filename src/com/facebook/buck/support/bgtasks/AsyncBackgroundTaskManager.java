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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous-enabled implementation of {@link BackgroundTaskManager}. Tasks run in a pool. Takes
 * a blocking flag in constructor; when {@code blocking=true}, manager waits for tasks to complete
 * before returning control to client. When {@code blocking=false}, manager schedules on a separate
 * thread and does not wait for task completion. Scheduler thread is paused whenever a new command
 * begins.
 *
 * <p>NOTE: only a manager on buckd should be set/instantiated to nonblocking mode, otherwise
 * unexpected behavior might occur
 */
public class AsyncBackgroundTaskManager extends BackgroundTaskManager {

  private static final Logger LOG = Logger.get(AsyncBackgroundTaskManager.class);
  private static final int DEFAULT_THREADS = 3;

  private final Queue<ManagedBackgroundTask<?>> scheduledTasks = new LinkedList<>();
  private final Map<Class<?>, ManagedBackgroundTask<?>> cancellableTasks =
      new ConcurrentHashMap<>();
  private final boolean blocking;

  private final AtomicBoolean schedulerRunning;
  private final AtomicInteger commandsRunning;
  private final AtomicBoolean schedulingOpen;

  private final Semaphore availableThreads;
  private final Optional<ExecutorService> scheduler;
  private final ExecutorService taskPool;
  private final ScheduledExecutorService timeoutPool;

  /**
   * Constructs an {@link AsyncBackgroundTaskManager}. If in nonblocking mode, sets up a scheduler
   * thread and pool for tasks.
   *
   * @param blocking bool indicating if this manager should block when running tasks or not
   * @param nThreads (optional) number of threads in pool. defaults to {@code DEFAULT_THREADS} if
   *     not provided
   */
  public AsyncBackgroundTaskManager(boolean blocking, int nThreads) {
    this.blocking = blocking;
    this.schedulerRunning = new AtomicBoolean(false);
    this.taskPool = Executors.newFixedThreadPool(nThreads);
    this.timeoutPool = Executors.newScheduledThreadPool(1);
    this.scheduler = blocking ? Optional.empty() : Optional.of(Executors.newFixedThreadPool(1));
    this.commandsRunning = new AtomicInteger(0);
    this.availableThreads = new Semaphore(nThreads);
    this.schedulingOpen = new AtomicBoolean(true);
  }

  public AsyncBackgroundTaskManager(boolean blocking) {
    this(blocking, DEFAULT_THREADS);
  }

  @Override
  public TaskManagerCommandScope getNewScope(BuildId buildId) {
    return new TaskManagerCommandScope(this, buildId);
  }

  private void startSchedulingIfNeeded() {
    Preconditions.checkState(scheduler.isPresent());
    if (schedulerRunning.getAndSet(true)) {
      return;
    }
    scheduler.get().submit(this::scheduleLoop);
  }

  private void shutDownScheduling() {
    schedulingOpen.set(false);
    try {
      if (!blocking) {
        scheduler.get().shutdownNow();
        scheduler.get().awaitTermination(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      scheduler.get().shutdownNow();
    }
  }

  /** Shut down scheduler and pool threads. */
  @Override
  public void shutdownNow() {
    shutDownScheduling();
    timeoutPool.shutdownNow(); // we lose timeouts on shutdown
    taskPool.shutdownNow();
  }

  @Override
  public void shutdown(long timeout, TimeUnit units) throws InterruptedException {
    shutDownScheduling();
    timeoutPool.shutdownNow(); // we lose timeouts on shutdown
    taskPool.shutdown();
    taskPool.awaitTermination(timeout, units);
  }

  @Override
  Future<Void> schedule(ManagedBackgroundTask<?> task) {
    if (!schedulingOpen.get()) {
      LOG.warn("Manager is not accepting new tasks; newly scheduled tasks will not be run.");
      return Futures.immediateCancelledFuture();
    }
    Class<?> actionClass = task.getActionClass();
    synchronized (cancellableTasks) {
      if (cancellableTasks.containsKey(actionClass)) {
        cancellableTasks.get(actionClass).markToCancel();
      }
      if (task.getShouldCancelOnRepeat()) {
        cancellableTasks.put(actionClass, task);
      }
    }
    synchronized (scheduledTasks) {
      scheduledTasks.add(task);
      scheduledTasks.notify();
    }
    return task.getFuture();
  }

  /**
   * Runs a task. Exceptions are caught and logged.
   *
   * @param managedTask Task to run
   */
  void runTask(ManagedBackgroundTask<?> managedTask) {
    managedTask.run();
  }

  private void addTimeoutIfNeeded(Future<?> taskHandler, ManagedBackgroundTask<?> task) {
    Optional<Timeout> timeout = task.getTimeout();
    if (timeout.isPresent()) {
      timeoutPool.schedule(
          () -> {
            if (taskHandler.cancel(true)) {
              LOG.warn(String.format("Task %s timed out", task.getId()));
            }
          },
          timeout.get().timeout(),
          timeout.get().unit());
    }
  }

  @Override
  void notify(Notification code) {
    if (blocking) {
      notifySync(code);
    } else {
      notifyAsync(code);
    }
  }

  private Future<?> submitTask(ManagedBackgroundTask<?> task) {
    Future<?> handler =
        taskPool.submit(
            () -> {
              runTask(task);
              availableThreads.release();
            });
    addTimeoutIfNeeded(handler, task);
    return handler;
  }

  private boolean taskCancelled(ManagedBackgroundTask<?> task) {
    cancellableTasks.remove(task.getActionClass(), task);
    return task.getToCancel();
  }

  private void notifySync(Notification code) {
    switch (code) {
      case COMMAND_START:
        commandsRunning.incrementAndGet();
        break;

      case COMMAND_END:
        if (commandsRunning.decrementAndGet() > 0) {
          return;
        }
        try {
          ArrayList<Future<?>> futures;
          synchronized (scheduledTasks) {
            futures = new ArrayList<>(scheduledTasks.size());
            while (!scheduledTasks.isEmpty()) {
              if (!schedulingOpen.get()) {
                break;
              }
              availableThreads.acquire();
              ManagedBackgroundTask<?> task = scheduledTasks.remove();
              if (taskCancelled(task)) {
                availableThreads.release();
                continue;
              }
              futures.add(submitTask(task));
            }
          }
          for (Future<?> future : futures) {
            try {
              future.get();
            } catch (ExecutionException e) {
              // task exceptions should normally be caught in runTask
              LOG.error(e, "Task threw exception");
            } catch (CancellationException e) {
              LOG.info(e, "Task was cancelled");
            }
          }
        } catch (InterruptedException e) {
          LOG.warn("Blocking manager interrupted");
        }
    }
  }

  private void notifyAsync(Notification code) {
    startSchedulingIfNeeded();
    switch (code) {
      case COMMAND_START:
        commandsRunning.incrementAndGet();
        break;

      case COMMAND_END:
        synchronized (commandsRunning) {
          commandsRunning.decrementAndGet();
          commandsRunning.notify();
        }
        break;
    }
  }

  private void scheduleLoop() {
    try {
      while (!Thread.interrupted()) {
        synchronized (scheduledTasks) {
          while (scheduledTasks.isEmpty()) {
            scheduledTasks.wait();
          }
        }
        synchronized (commandsRunning) {
          while (commandsRunning.get() > 0) {
            commandsRunning.wait();
          }
        }
        availableThreads.acquire();
        ManagedBackgroundTask<?> task;
        synchronized (scheduledTasks) {
          task = scheduledTasks.remove();
        }
        if (taskCancelled(task)) {
          availableThreads.release();
          continue;
        }
        submitTask(task);
      }
    } catch (InterruptedException e) {
      // do nothing. got interrupted.
    }
    LOG.info("Scheduler thread interrupted; shutting down manager");
    if (schedulingOpen.get()) {
      schedulingOpen.set(false);
      taskPool.shutdownNow();
    }
  }

  /**
   * Return list of currently scheduled (not yet submitted) tasks. For debugging/testing.
   *
   * @return list of currently scheduled tasks
   */
  @VisibleForTesting
  protected Queue<ManagedBackgroundTask<?>> getScheduledTasks() {
    return scheduledTasks;
  }

  /**
   * Return map of tasks that might be cancelled (i.e. not run) if a task with the same action is
   * subsequently scheduled. Tasks in this map are currently scheduled, not yet run. For
   * debugging/testing.
   *
   * @return map of cancellable tasks
   */
  @VisibleForTesting
  protected Map<Class<?>, ManagedBackgroundTask<?>> getCancellableTasks() {
    return cancellableTasks;
  }

  /**
   * Check if the manager is shut down. "Shut down" means that all executors are terminated and
   * manager is no longer accepting new task submissions. For debugging/testing.
   *
   * @return true if manager is shut down, false otherwise
   */
  @VisibleForTesting
  protected boolean isShutDown() {
    return !schedulingOpen.get();
  }
}
