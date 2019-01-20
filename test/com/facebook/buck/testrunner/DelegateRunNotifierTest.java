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

package com.facebook.buck.testrunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

public class DelegateRunNotifierTest {

  @Test
  public void hasJunitTimeoutReturnsFalseWhenNoTimeout() throws InitializationError {
    DelegateRunNotifier notifier = createDelegateRunNotifier(FakeNoTimeoutTestClass.class, 50);
    assertFalse(
        notifier.hasJunitTimeout(
            Description.createTestDescription(FakeNoTimeoutTestClass.class, "FakeTestClass")));

    notifier =
        createDelegateRunNotifier(
            new FakeRunner(FakeNoTimeoutTestClassWithExtraConstructor.class), 50);
    assertFalse(
        notifier.hasJunitTimeout(getDescriptionFromTestClass(FakeNoTimeoutTestClass.class)));
  }

  @Test
  public void hasJunitTimeoutReturnTrueWhenTimeout() throws InitializationError {
    DelegateRunNotifier notifier = createDelegateRunNotifier(FakeTestClassWithTimeout.class, 50);
    assertTrue(
        notifier.hasJunitTimeout(getDescriptionFromTestClass(FakeTestClassWithTimeout.class)));

    notifier =
        createDelegateRunNotifier(
            new FakeRunner(FakeTestClassWithTimeoutWithExtraConstructor.class), 50);
    assertTrue(
        notifier.hasJunitTimeout(
            getDescriptionFromTestClass(FakeTestClassWithTimeoutWithExtraConstructor.class)));
  }

  @Test
  public void hasJunitTimeoutReturnsTrueWhenTimeoutRule() throws InitializationError {
    DelegateRunNotifier notifier =
        createDelegateRunNotifier(FakeTestClassWithTimeoutRule.class, 50);
    assertTrue(
        notifier.hasJunitTimeout(
            Description.createTestDescription(
                FakeTestClassWithTimeoutRule.class, "FakeTestClass")));

    notifier =
        createDelegateRunNotifier(
            new FakeRunner(FakeTestClassWithTimeoutRuleWithExtraConstructor.class), 50);
    assertTrue(
        notifier.hasJunitTimeout(
            getDescriptionFromTestClass(FakeTestClassWithTimeoutRuleWithExtraConstructor.class)));
  }

  private static Description getDescriptionFromTestClass(Class<?> clazz) {
    return Description.createTestDescription(
        clazz, clazz.getSimpleName(), findMethodAnnotations(clazz));
  }

  private DelegateRunNotifier createDelegateRunNotifier(
      Class<?> testClass, int defaultTimeoutMillis) throws InitializationError {
    return createDelegateRunNotifier(
        new BuckBlockJUnit4ClassRunner(testClass, defaultTimeoutMillis), defaultTimeoutMillis);
  }

  private DelegateRunNotifier createDelegateRunNotifier(Runner runner, int defaultTimeoutMillis) {
    return new DelegateRunNotifier(runner, new RunNotifier(), defaultTimeoutMillis);
  }

  private static Annotation[] findMethodAnnotations(Class<?> clazz) {
    List<Annotation> annotationList = new ArrayList<>();
    for (Method m : clazz.getMethods()) {
      annotationList.addAll(Arrays.asList(m.getAnnotations()));
    }
    return annotationList.toArray(new Annotation[0]);
  }

  private static class FakeRunner extends Runner {

    private Class<?> clazz;

    public FakeRunner(Class<?> testClass) {
      this.clazz = testClass;
    }

    @Override
    public Description getDescription() {
      return Description.createTestDescription(
          clazz, clazz.getSimpleName(), findMethodAnnotations(clazz));
    }

    @Override
    public void run(RunNotifier notifier) {}
  }

  public static class FakeNoTimeoutTestClass {
    @Test
    @Ignore
    public void fakeTestMethod() {
      assertEquals(0, 0);
    }
  }

  public static class FakeNoTimeoutTestClassWithExtraConstructor {
    @SuppressWarnings("unused")
    public FakeNoTimeoutTestClassWithExtraConstructor(Object ignored) {}

    @Test
    @Ignore
    public void fakeTestMethod() {
      assertEquals(1, 1);
    }
  }

  public static class FakeTestClassWithTimeout {
    @Test(timeout = 5)
    @Ignore
    public void fakeTestMethod() {
      assertEquals(2, 2);
    }
  }

  public static class FakeTestClassWithTimeoutWithExtraConstructor {
    @SuppressWarnings("unused")
    public FakeTestClassWithTimeoutWithExtraConstructor(Object ignored) {}

    @Test(timeout = 5)
    @Ignore
    public void fakeTestMethod() {
      assertEquals(3, 3);
    }
  }

  public static class FakeTestClassWithTimeoutRule {

    public @Rule Timeout rule = Timeout.seconds(1);

    @Test(timeout = 5)
    @Ignore
    public void fakeTestMethod() {
      assertEquals(4, 4);
    }
  }

  public static class FakeTestClassWithTimeoutRuleWithExtraConstructor {

    public @Rule Timeout rule = Timeout.seconds(1);

    @SuppressWarnings("unused")
    public FakeTestClassWithTimeoutRuleWithExtraConstructor(Object ignored) {}

    @Test(timeout = 5)
    @Ignore
    public void fakeTestMethod() {
      assertEquals(5, 5);
    }
  }
}
