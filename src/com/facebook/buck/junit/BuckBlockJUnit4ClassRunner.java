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
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * JUnit-4-compatible test class runner that supports the concept of a "default timeout." If the
 * value of {@code defaultTestTimeoutMillis} passed to the constructor is non-zero, then it will be
 * used in place of {@link Test#timeout()} if {@link Test#timeout()} returns zero.
 * <p>
 * The superclass, {@link BlockJUnit4ClassRunner}, was introduced in JUnit 4.5 as a published API
 * that was designed to be extended.
 * <p>
 * This runner also creates Descriptions that allow JUnitRunner to filter which test-methods to run
 * and should be forced into the test code path whenever test-selectors are in use.
 */
public class BuckBlockJUnit4ClassRunner extends BlockJUnit4ClassRunner {

  // We create an ExecutorService based on the implementation of
  // Executors.newSingleThreadExecutor(). The problem with Executors.newSingleThreadExecutor() is
  // that it does not let us specify a RejectedExecutionHandler, which we need to ensure that
  // garbage is not spewed to the user's console if the build fails.
  private final ThreadLocal<ExecutorService> executor = new ThreadLocal<ExecutorService>() {
    @Override
    protected ExecutorService initialValue() {
      return MoreExecutors.newSingleThreadExecutor(getClass().getSimpleName());
    }
  };

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
    Callable<Object> maker = new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        return BuckBlockJUnit4ClassRunner.super.createTest();
      }
    };

    Future<Object> createdTest = executor.get().submit(maker);
    return createdTest.get();
  }

  private boolean isNeedingCustomTimeout() {
    return defaultTestTimeoutMillis <= 0 || hasTimeoutRule(getTestClass());
  }

  private long getTimeout(FrameworkMethod method) {
    // Check to see if the method has declared a timeout on the @Test annotation. If that's present
    // and set, then let JUnit handle the timeout for us, which (counter-intuitively) means letting
    // the test run indefinitely.
    Test annotation = method.getMethod().getAnnotation(Test.class);
    if (annotation != null) {
      long timeout = annotation.timeout();
      if (timeout != 0) {  // 0 represents the default timeout
        return Long.MAX_VALUE;
      }
    }

    if (!isNeedingCustomTimeout()) {
      return defaultTestTimeoutMillis;
    }
    return Long.MAX_VALUE;
  }

  /**
   * Override the default timeout behavior so that when no timeout is specified in the {@link Test}
   * annotation, the timeout specified by the constructor will be used (if it has been set).
   */
  @Override
  protected Statement methodBlock(FrameworkMethod method) {
    Statement statement = super.methodBlock(method);

    // If the test class has a Timeout @Rule, then that should supersede the default timeout. The
    // same applies if it has a timeout value for the @Test annotation.

    long timeout = getTimeout(method);

    return new SameThreadFailOnTimeout(
          executor.get(),
          timeout,
          testName(method),
          statement);
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
