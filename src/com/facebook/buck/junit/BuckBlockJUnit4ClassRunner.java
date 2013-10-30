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

import com.facebook.buck.util.concurrent.MoreExecutors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JUnit-4-compatible test class runner that supports the concept of a "default timeout." If the
 * value of {@code defaultTestTimeoutMillis} passed to the constructor is non-zero, then it will be
 * used in place of {@link Test#timeout()} if {@link Test#timeout()} returns zero.
 * <p>
 * The superclass, {@link BlockJUnit4ClassRunner}, was introduced in JUnit 4.5 as a published API
 * that was designed to be extended.
 */
public class BuckBlockJUnit4ClassRunner extends BlockJUnit4ClassRunner {

  // We create an ExecutorService based on the implementation of
  // Executors.newSingleThreadExecutor(). The problem with Executors.newSingleThreadExecutor() is
  // that it does not let us specify a RejectedExecutionHandler, which we need to ensure that
  // garbage is not spewed to the user's console if the build fails.
  private final ExecutorService executor = MoreExecutors.newSingleThreadExecutor();

  private final long defaultTestTimeoutMillis;

  public BuckBlockJUnit4ClassRunner(Class<?> klass, long defaultTestTimeoutMillis)
      throws InitializationError {
    super(klass);
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
  }

  @Override
  protected Object createTest() throws Exception {
    // Pushing tests onto threads because the test timeout has been set is Unexpected Behaviour. It
    // causes things like the SingleThreadGuard in jmock2 to get most upset because the test is
    // being run on a thread different from the one it was created on. Work around this by creating
    // the test on the same thread we will be timing it out on.
    // See https://github.com/junit-team/junit/issues/686 for more context.
    if (isNeedingCustomTimeout()) {
      return super.createTest();
    }

    Callable<Object> maker = new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        return BuckBlockJUnit4ClassRunner.super.createTest();
      }
    };

    Future<Object> createdTest = executor.submit(maker);
    return createdTest.get();
  }

  private boolean isNeedingCustomTimeout() {
    return defaultTestTimeoutMillis <= 0 || hasTimeoutRule(getTestClass());
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
   * Override the default timeout behavior so that when no timeout is specified in the {@link Test}
   * annotation, the timeout specified by the constructor will be used (if it has been set).
   */
  @Override
  protected Statement methodBlock(FrameworkMethod method) {
    Statement statement = super.methodBlock(method);

    // If the test class has a Timeout @Rule, then that should supersede the default timeout.
    if (!isNeedingCustomTimeout()) {
      statement = new SameThreadFailOnTimeout(testName(method), statement);
    }

    return statement;
  }

  private class SameThreadFailOnTimeout extends Statement {
    private final Callable<Throwable> callable;
    private final String testName;

    public SameThreadFailOnTimeout(String testName, final Statement next) {
      this.testName = testName;
      this.callable = new Callable<Throwable>() {
        @Override
        public Throwable call() {
          try {
            next.evaluate();
            return null;
          } catch (Throwable throwable) {
            return throwable;
          }
        }
      };
    }

    @Override
    public void evaluate() throws Throwable {
      Future<Throwable> submitted = executor.submit(callable);
      try {
        Throwable result = submitted.get(defaultTestTimeoutMillis, TimeUnit.MILLISECONDS);
        if (result != null) {
          throw result;
        }
      } catch (TimeoutException e) {
        submitted.cancel(true);
        // The default timeout doesn't indicate which test was running.
        String message = String.format("test %s timed out after %d milliseconds",
            testName,
            defaultTestTimeoutMillis);

        throw new Exception(message);
      }
    }
  }

  /**
   * @return {@code true} if the test class has any fields annotated with {@code Rule} whose type
   *     is {@link Timeout}.
   */
  static boolean hasTimeoutRule(TestClass testClass) {
    // Many protected convenience methods in BlockJUnit4ClassRunner that are available in JUnit 4.11
    // such as getTestRules(Object) were not public until
    // https://github.com/junit-team/junit/commit/8782efa08abf5d47afdc16740678661443706740,
    // which appears to be JUnit 4.9. Because we allow users to use JUnit 4.7, we need to include a
    // custom implementation that is backwards compatible to JUnit 4.7.
    List<FrameworkField> fields = testClass.getAnnotatedFields(Rule.class);
    for (FrameworkField field : fields) {
      if (field.getField().getType().equals(Timeout.class)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Override default init error collector so that class without any test methods will pass
   */
  @Override
  protected void collectInitializationErrors(List<Throwable> errors) {
    if (!computeTestMethods().isEmpty()) {
      super.collectInitializationErrors(errors);
    }
  }

}
