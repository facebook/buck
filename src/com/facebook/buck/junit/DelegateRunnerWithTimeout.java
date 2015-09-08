/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.junit;

import com.facebook.buck.util.concurrent.MoreExecutors;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Runner} that composes a {@link Runner} that enforces a default timeout when running a
 * test.
 */
class DelegateRunnerWithTimeout extends Runner {

  /**
   * {@link ExecutorService} on which all tests run by this {@link Runner} are executed.
   * <p>
   * In Robolectric, the {@code ShadowLooper.resetThreadLoopers()} asserts that the current thread
   * is the same as the thread on which the {@code ShadowLooper} class was loaded. Therefore, to
   * preserve the behavior of the {@code org.robolectric.RobolectricTestRunner}, we use an
   * {@link ExecutorService} to create and run the test on. This has the unfortunate side effect of
   * creating one thread per runner, but JUnit ensures that they're all called serially, so the
   * overall effect is that of having only a single thread.
   * <p>
   * We use a {@link ThreadLocal} so that if a test spawns more tests that create their own runners
   * we don't deadlock.
   */
  private static final ThreadLocal<ExecutorService> executor = new ThreadLocal<ExecutorService>() {
    @Override
    protected ExecutorService initialValue() {
      return MoreExecutors.newSingleThreadExecutor(DelegateRunnerWithTimeout.class.getSimpleName());
    }
  };

  private final Runner delegate;
  private final long defaultTestTimeoutMillis;

  DelegateRunnerWithTimeout(Runner delegate, long defaultTestTimeoutMillis) {
    if (defaultTestTimeoutMillis <= 0) {
      throw new IllegalArgumentException(String.format(
          "defaultTestTimeoutMillis must be greater than zero but was: %s.",
          defaultTestTimeoutMillis));
    }
    this.delegate = delegate;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
  }

  /**
   * @return the description from the original {@link Runner} wrapped by this {@link Runner}.
   */
  @Override
  public Description getDescription() {
    return delegate.getDescription();
  }

  /**
   * Runs the tests for this runner, but wraps the specified {@code notifier} with a
   * {@link DelegateRunNotifier} that intercepts calls to the original {@code notifier}.
   * The {@link DelegateRunNotifier} is what enables us to impose our default timeout.
   */
  @Override
  public void run(RunNotifier notifier) {
    final DelegateRunNotifier wrapper = new DelegateRunNotifier(
        delegate, notifier, defaultTestTimeoutMillis);

    if (wrapper.hasJunitTimeout(getDescription())) {
      runWithoutBuckManagedTimeout(wrapper);
    } else {
      runWithBuckManagedTimeout(wrapper);
    }
  }

  private void runWithBuckManagedTimeout(final DelegateRunNotifier wrapper) {
    final Semaphore completionSemaphore = new Semaphore(1);
    final AtomicBoolean testsCompleted = new AtomicBoolean(false);

    // Acquire the one permit so later tryAcquire attempts lock
    // until they either time out or this permit is released by
    // DeletgateRunNotifier.onTestRunFinished()
    try {
      completionSemaphore.acquire();
    } catch (InterruptedException e) {
      // If the aquire is interrupted
    }

    // We run the Runner in an Executor so that we can tear it down if we need to.
    executor.get().submit(
        new Runnable() {
          @Override
          public void run() {
            try {
              delegate.run(wrapper);
            } finally {
              if (!wrapper.hasTestThatExceededTimeout()) {
                testsCompleted.set(true);
              }
              completionSemaphore.release();
            }
          }
        });

    // We poll the Executor to see if the Runner is complete. In the event that a test has exceeded
    // the default timeout, we cancel the Runner to protect against the case where the test hangs
    // forever.
    while (true) {
      if (testsCompleted.get()) {
        // Normal termination: hooray!
        return;
      }

      if (wrapper.hasTestThatExceededTimeout()) {
        // The test results that have been reported to the RunNotifier should still be output, but
        // there may be tests that did not have a chance to run. Unfortunately, we have no way to
        // tell the Runner to cancel only the runaway test.
        executor.get().shutdownNow();
        return;
      }

      // Tests are still running, so wait and try again.
      try {
        if (completionSemaphore.tryAcquire(250L, TimeUnit.MILLISECONDS)) {
          completionSemaphore.release();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void runWithoutBuckManagedTimeout(final DelegateRunNotifier wrapper) {
    delegate.run(wrapper);
  }

}
