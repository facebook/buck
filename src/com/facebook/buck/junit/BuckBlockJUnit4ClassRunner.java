/*
 * Copyright 2012-present Facebook, Inc.
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
import org.junit.internal.runners.statements.FailOnTimeout;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

import java.lang.reflect.Method;

/**
 * JUnit-4-compatible test class runner that supports the concept of a "default timeout." If the
 * value of {@code defaultTestTimeoutMillis} passed to the constructor is non-zero, then it will be
 * used in place of {@link Test#timeout()} if {@link Test#timeout()} returns zero.
 * <p>
 * The superclass, {@link BlockJUnit4ClassRunner}, was introduced in JUnit 4.5 as a published API
 * that was designed to be extended.
 */
public class BuckBlockJUnit4ClassRunner extends BlockJUnit4ClassRunner {

  private final long defaultTestTimeoutMillis;

  public BuckBlockJUnit4ClassRunner(Class<?> klass, long defaultTestTimeoutMillis)
      throws InitializationError {
    super(klass);
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
  }

  /**
   * Convenience method to run a single test method under JUnit.
   */
  public Result runTest(Method testMethod) throws NoTestsRemainException {
    // We need a Description for the method we want to test.
    Description description = Description.createTestDescription(
        getTestClass().getJavaClass(),
        testMethod.getName(),
        testMethod.getAnnotations());

    // Filter this runner so it only runs a single test method.
    Filter singleMethodFilter = Filter.matchMethodDescription(description);
    filter(singleMethodFilter);

    /*
     * What follows is the implementation of JUnitCore.run(Runner) from JUnit 4.11. In JUnit 4.11,
     * the Javadoc for that method states "Do not use. Testing purposes only," so we have copied
     * the implementation here in case the method is removed in future versions of JUnit.
     */

    // We create a Result whose details will be updated by a RunListener that is attached to a
    // RunNotifier.
    Result result = new Result();
    RunListener listener = result.createListener();
    RunNotifier runNotifier = new RunNotifier();

    // The Result's listener must be first according to comments in JUnit's source.
    runNotifier.addFirstListener(listener);

    try {
      // Run the test.
      runNotifier.fireTestRunStarted(description);
      run(runNotifier);
      runNotifier.fireTestRunFinished(result);
    } finally {
      // Make sure the notifier's reference to the listener is cleaned up.
      runNotifier.removeListener(listener);
    }

    // Return the result that should have been populated by the listener/notifier combo.
    return result;
  }

  /**
   * Override the default timeout behavior so that when no timeout is specified in the {@link @Test}
   * annotation, the timeout specified by the constructor will be used (if it has been set).
   * <p>
   * <strong>IMPORTANT</strong> In JUnit 4.11, this method is tagged as deprecated with the note:
   * "Will be private soon: use Rules instead." That suggests that we should override
   * {@link #getTestRules(Object)} so that it includes an additional {@link Timeout}, if
   * appropriate. However, {@link Timeout} was not introduced until JUnit 4.7 and {@link TestRule}
   * was not introduced until JUnit 4.9, so the current solution is more backwards-compatible. We
   * will likely have to revisit this in the future.
   */
  @Override
  protected Statement withPotentialTimeout(FrameworkMethod method, Object test, Statement next) {
    long timeout = getTimeout(method.getAnnotation(Test.class));
    if (timeout > 0) {
      return new FailOnTimeout(next, timeout);
    } else if (defaultTestTimeoutMillis > 0) {
      return new FailOnTimeout(next, defaultTestTimeoutMillis);
    } else {
      return next;
    }
  }

  // Copied from BuckBlockJUnit4ClassRunner in JUnit 4.11.
  private long getTimeout(Test annotation) {
    if (annotation == null) {
      return 0;
    }
    return annotation.timeout();
  }

}
