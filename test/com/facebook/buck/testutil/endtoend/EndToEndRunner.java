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

package com.facebook.buck.testutil.endtoend;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.internal.runners.model.ReflectiveCallable;
import org.junit.internal.runners.statements.Fail;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * {@link EndToEndRunner} is a JUnit test runner that allows the test class to run test methods
 * (marked by @Test annotation) against many configurations defined by the class' environments
 * (marked by @Environment and @EnvironmentFor annotations). It will run these methods as if they
 * were separate tests.
 *
 * <p>To use, mark your test class with @RunWith(EndToEndRunnerTest.class) and include a static
 * method that returns an {@link EndToEndEnvironment}, marked with an @Environment annotation.
 *
 * <p>You can use {@link EndToEndRunnerTest} as an example of this class' usage.
 */
public class EndToEndRunner extends ParentRunner<EndToEndTestDescriptor> {
  private Map<String, Optional<EndToEndEnvironment>> environmentMap = new HashMap<>();
  private EndToEndEnvironment defaultEnv;
  private Throwable setupError;

  public EndToEndRunner(Class<?> testClass) throws InitializationError {
    super(testClass);
  }

  private Optional<EndToEndEnvironment> getEnvironmentFromAnnotatedMethod(
      FrameworkMethod environmentMethod) {
    try {
      Object methodResult = environmentMethod.getMethod().invoke(getTestClass());
      EndToEndEnvironment environment = EndToEndEnvironment.class.cast(methodResult);
      return Optional.of(environment);
    } catch (IllegalAccessException | InvocationTargetException e) {
      // Should not get an exception as we validate beforehand that the return types are
      // environments
      if (setupError == null) {
        setupError = e;
      }
      return Optional.empty();
    }
  }

  /**
   * Finds method marked with @Environment, and casts the returned object as the class' default
   * {@link EndToEndEnvironment}.
   */
  private void constructDefaultEnvironment() {
    List<FrameworkMethod> environmentMethods =
        getTestClass().getAnnotatedMethods(Environment.class);
    // Will return validation errors if empty, so no tests will be run anyway
    if (environmentMethods.isEmpty()) {
      return;
    }
    Optional<EndToEndEnvironment> defaultEnv =
        getEnvironmentFromAnnotatedMethod(environmentMethods.get(0));
    if (defaultEnv.isPresent()) {
      this.defaultEnv = defaultEnv.get();
    }
  }

  /**
   * Finds method marked with @EnvironmentFor, and casts the returned object as particular test's
   * environments (using environmentMap to go from methodName to environment)
   */
  private void constructEnvironmentMap() {
    List<FrameworkMethod> environmentForMethods =
        getTestClass().getAnnotatedMethods(EnvironmentFor.class);
    for (FrameworkMethod environmentForMethod : environmentForMethods) {
      Optional<EndToEndEnvironment> env = getEnvironmentFromAnnotatedMethod(environmentForMethod);
      String[] testsFor = environmentForMethod.getAnnotation(EnvironmentFor.class).testNames();
      for (String testFor : testsFor) {
        this.environmentMap.put(testFor, env);
      }
    }
  }

  /**
   * Marks validation errors in errors if:
   *
   * <ul>
   *   <li>There is not exactly one static method marked by @Environment
   *   <li>The method marked by @Environment does not return an {@link EndToEndEnvironment}
   * </ul>
   */
  private void validateDefaultEnvironment(List<Throwable> errors) {
    List<FrameworkMethod> environmentMethods =
        getTestClass().getAnnotatedMethods(Environment.class);
    if (environmentMethods.isEmpty()) {
      errors.add(
          new IllegalArgumentException(
              "Class requires a default environment, please add a static method marked"
                  + " by @Environment that returns an EndToEndEnvironment to provide the runner"
                  + " with a default environment. Example:\n\n"
                  + "@Environment\n"
                  + "EndToEndEnvironment createEndToEndEnvironment() {\n\n}"));
    } else if (environmentMethods.size() > 1) {
      errors.add(
          new IllegalArgumentException(
              "More than one default environment found. If you would like to use an environment"
                  + "for any particular tests, use the @EnvironmentFor annotation. Example: \n\n"
                  + "@EnvironmentFor(\"testNameShouldBuild\")\n"
                  + "EndToEndEnvironment createEndToEndEnvironmentForCase() {\n\n}"));
    } else {
      // TODO: validate method is static
      FrameworkMethod environmentMethod = environmentMethods.get(0);
      if (environmentMethod.getReturnType() != EndToEndEnvironment.class) {
        errors.add(
            new IllegalArgumentException(
                "Methods marked by @Environment must return an EndToEndEnvironment"));
      }
    }
  }

  /**
   * Marks validation errors in errors if:
   *
   * <ul>
   *   <li>Any method marked by @EnvironmentFor does not return and {@link EndToEndEnvironment}
   * </ul>
   */
  private void validateEnvironmentMap(List<Throwable> errors) {
    List<FrameworkMethod> environmentForMethods =
        getTestClass().getAnnotatedMethods(EnvironmentFor.class);
    for (FrameworkMethod environmentForMethod : environmentForMethods) {
      if (environmentForMethod.getReturnType() != EndToEndEnvironment.class) {
        errors.add(
            new Exception("Methods marked by @Environment must return an EndToEndEnvironment"));
      }
    }
    // TODO: Handle case where environment for for two tests, tests that don't exist
  }

  private void validateEnvironments(List<Throwable> errors) {
    validateDefaultEnvironment(errors);
    validateEnvironmentMap(errors);
  }

  /**
   * Marks validation errors in errors if:
   *
   * <ul>
   *   <li>Any method marked by @Test is a not a public non-static void method.
   * </ul>
   */
  private void validateTestMethods(List<Throwable> errors) {
    List<FrameworkMethod> methods = getTestClass().getAnnotatedMethods(Test.class);

    for (FrameworkMethod testMethod : methods) {
      testMethod.validatePublicVoid(false, errors);
      // TODO: validate they have the two parameters we give them
    }
  }

  private EndToEndEnvironment getEnvironmentForMethod(FrameworkMethod testMethod) {
    String methodName = testMethod.getName();
    Optional<EndToEndEnvironment> environment =
        this.environmentMap.getOrDefault(methodName, Optional.empty());
    return environment.orElse(this.defaultEnv);
  }

  /**
   * Builds TestDescriptors for every @Test method in the TestClass, for each configuration in that
   * method's designated {@link EndToEndEnvironment}
   */
  private List<EndToEndTestDescriptor> computeTestDescriptors() {
    ArrayList<EndToEndTestDescriptor> output = new ArrayList<>();
    List<FrameworkMethod> testMethods = getTestClass().getAnnotatedMethods(Test.class);
    for (FrameworkMethod testMethod : testMethods) {
      EndToEndEnvironment testEnvironment = getEnvironmentForMethod(testMethod);
      for (String[] templateSet : testEnvironment.getTemplates()) {
        for (Map<String, String> variableMap : testEnvironment.getVariableMaps()) {
          ToggleState toggleState = testEnvironment.getBuckdToggled();
          String[] commandSet = testEnvironment.getCommand();
          if (toggleState == ToggleState.ON_OFF || toggleState == ToggleState.ON) {
            EndToEndTestDescriptor testDescriptor =
                new EndToEndTestDescriptor(testMethod, templateSet, commandSet, true, variableMap);
            output.add(testDescriptor);
          }
          if (toggleState == ToggleState.ON_OFF || toggleState == ToggleState.OFF) {
            EndToEndTestDescriptor testDescriptor =
                new EndToEndTestDescriptor(testMethod, templateSet, commandSet, false, variableMap);
            output.add(testDescriptor);
          }
        }
      }
    }
    return output;
  }

  private Object createTest() throws Exception {
    return getTestClass().getOnlyConstructor().newInstance();
  }

  /** Creates the test statement for a testDescriptor */
  private Statement methodBlock(final EndToEndTestDescriptor testDescriptor) {
    Object test;
    try {
      test =
          new ReflectiveCallable() {
            @Override
            protected Object runReflectiveCall() throws Throwable {
              return createTest();
            }
          }.run();
    } catch (Throwable e) {
      return new Fail(e);
    }

    return new BuckInvoker(testDescriptor, test);
  }

  @Override
  protected void collectInitializationErrors(List<Throwable> errors) {
    super.collectInitializationErrors(errors);
    validateTestMethods(errors);
    validateEnvironments(errors);
  }

  /**
   * Needed for ParentRunner implementation, getChildren setups our environments, and returns
   * testDescriptors for each method against each configuration in their respective {@link
   * EndToEndEnvironment}s.
   */
  @Override
  protected List<EndToEndTestDescriptor> getChildren() {
    constructDefaultEnvironment();
    constructEnvironmentMap();
    if (setupError != null) {
      List<EndToEndTestDescriptor> failedDescriptors = new ArrayList<>();
      failedDescriptors.add(EndToEndTestDescriptor.failedSetup(getTestClass().getName()));
      return failedDescriptors;
    }
    return computeTestDescriptors();
  }

  /**
   * Needed for ParentRunner implementation, describeChild creates a testDescription from the
   * testMethod and the name we get from the test descriptor built off of environment configuration.
   */
  @Override
  protected Description describeChild(EndToEndTestDescriptor child) {
    if (setupError != null) {
      return Description.createTestDescription(getTestClass().getJavaClass(), child.getName());
    }
    return Description.createTestDescription(
        getTestClass().getJavaClass(), child.getName(), child.getMethod().getAnnotations());
  }

  /**
   * Needed for ParentRunner implementation, runChild runs a child and then notifies the parent's
   * RunNotifier with the test Result.
   */
  @Override
  protected void runChild(EndToEndTestDescriptor child, final RunNotifier notifier) {
    Description description = describeChild(child);
    Statement statement;
    if (setupError != null) {
      statement = new Fail(setupError);
    } else {
      statement =
          new Statement() {
            @Override
            public void evaluate() throws Throwable {
              methodBlock(child).evaluate();
            }
          };
    }
    runLeaf(statement, description, notifier);
  }
}
