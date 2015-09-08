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

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.TestClass;

import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RunNotifier} that sets a timer when a test starts. The default timeout specified in
 * {@code .buckconfig} is the length of the timer. When the timer goes off, it checks if the test
 * has finished. If it has not finished, the test is flagged as a failure, and all future updates to
 * the test status are ignored.
 */
class DelegateRunNotifier extends RunNotifier {

  private final Runner runner;
  private final RunNotifier delegate;
  private final Set<Description> finishedTests;
  private final long defaultTestTimeoutMillis;
  private final Timer timer;

  /** Flag that will be set if a test exceeds {@link #defaultTestTimeoutMillis}. */
  private final AtomicBoolean hasTestThatExceededTimeout;

  DelegateRunNotifier(Runner runner, RunNotifier delegate, long defaultTestTimeoutMillis) {
    this.runner = runner;
    this.delegate = delegate;
    this.finishedTests = new HashSet<Description>();
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.timer = new Timer();
    this.hasTestThatExceededTimeout = new AtomicBoolean(false);

    // Because our fireTestRunFinished() does not seem to get invoked, we listen for the
    // delegate to fire a testRunFinished event so we can dispose of the timer.
    delegate.addListener(
        new RunListener() {
          @Override
          public void testRunFinished(Result result) throws Exception {
            onTestRunFinished();
          }
        });
  }

  /** Performs any cleanup that we need to do as a result of the test run being complete. */
  private void onTestRunFinished() {
    timer.cancel();
  }

  /**
   * Method that can be polled to see whether a test has exceeded its default timeout. If a test
   * hangs forever, then the Runner will never start the next test, even if it was the last test and
   * we invoked fireTestFinished() on the Runner's RunNotifier. For this reason, an external process
   * should monitor the state of this method and cancel the Runner, if appropriate.
   */
  public boolean hasTestThatExceededTimeout() {
    return hasTestThatExceededTimeout.get();
  }

  @Override
  public void addFirstListener(RunListener listener) {
    delegate.addFirstListener(listener);
  }

  @Override
  public void addListener(RunListener listener) {
    delegate.addListener(listener);
  }

  @Override
  public void removeListener(RunListener listener) {
    delegate.removeListener(listener);
  }

  @Override
  public void fireTestRunStarted(Description description) {
    // This method does not appear to be invoked. Presumably whoever has a reference to the original
    // delegate is invoking its fireTestRunStarted(Description) method directly.
    delegate.fireTestRunStarted(description);
  }

  @Override
  public void fireTestRunFinished(Result result) {
    // This method does not appear to be invoked. Presumably whoever has a reference to the original
    // delegate is invoking its fireTestRunFinished(Description) method directly.
    timer.cancel();
    delegate.fireTestRunFinished(result);
  }

  @Override
  public void fireTestStarted(final Description description) throws StoppedByUserException {
    delegate.fireTestStarted(description);

    // Do not do apply the default timeout if the test has its own @Test(timeout).
    if (hasJunitTimeout(description)) {
      return;
    }

    // Schedule a timer that verifies that the test completed within the specified timeout.
    TimerTask task = new TimerTask() {
      @Override
      public void run() {
        synchronized (finishedTests) {
          // If the test already finished, then do nothing.
          if (finishedTests.contains(description)) {
            return;
          }

          hasTestThatExceededTimeout.set(true);

          // Should report the failure. The Exception is modeled after the one created by
          // org.junit.internal.runners.statements.FailOnTimeout#createTimeoutException(Thread).
          Exception exception = new Exception(String.format(
              "test timed out after %d milliseconds", defaultTestTimeoutMillis));
          Failure failure = new Failure(description, exception);
          fireTestFailure(failure);
          fireTestFinished(description);

          if (!finishedTests.contains(description)) {
            throw new IllegalStateException("fireTestFinished() should update finishedTests.");
          }

          onTestRunFinished();
        }
      }
    };
    timer.schedule(task, defaultTestTimeoutMillis);
  }

  boolean hasJunitTimeout(Description description) {
    // Do not do apply the default timeout if the test has its own @Test(timeout).
    Test testAnnotation = description.getAnnotation(Test.class);
    if (testAnnotation != null && testAnnotation.timeout() > 0) {
      return true;
    }

    // Do not do apply the default timeout if the test has its own @Rule Timeout.
    TestClass testClass = getTestClass(description);
    if (BuckBlockJUnit4ClassRunner.hasTimeoutRule(testClass)) {
      return true;
    }

    return false;
  }


  private TestClass getTestClass(Description description) {
    if (runner instanceof ParentRunner) {
      return ((ParentRunner<?>) runner).getTestClass();
    } else {
      Class<?> testClass = description.getTestClass();
      return new TestClass(testClass);
    }
  }

  @Override
  public void fireTestFailure(Failure failure) {
    synchronized (finishedTests) {
      if (!finishedTests.contains(failure.getDescription())) {
        delegate.fireTestFailure(failure);
      }
    }
  }

  @Override
  public void fireTestAssumptionFailed(Failure failure) {
    // This is fired when there is a failure for a org.junit.Assume.assumeXXX() method.
    synchronized (finishedTests) {
      if (!finishedTests.contains(failure.getDescription())) {
        delegate.fireTestAssumptionFailed(failure);
      }
    }
  }

  @Override
  public void fireTestIgnored(Description description) {
    synchronized (finishedTests) {
      if (!finishedTests.contains(description)) {
        delegate.fireTestIgnored(description);
      }
    }
  }

  @Override
  public void fireTestFinished(Description description) {
    synchronized (finishedTests) {
      if (!finishedTests.contains(description)) {
        delegate.fireTestFinished(description);
        finishedTests.add(description);
      }
    }
  }

  @Override
  public void pleaseStop() {
    delegate.pleaseStop();
  }
}
