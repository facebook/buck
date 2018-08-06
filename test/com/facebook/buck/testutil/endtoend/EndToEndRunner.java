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

import static org.junit.internal.runners.rules.RuleMemberValidator.RULE_METHOD_VALIDATOR;
import static org.junit.internal.runners.rules.RuleMemberValidator.RULE_VALIDATOR;

import com.facebook.buck.testutil.ProcessResult;
import java.lang.annotation.AnnotationFormatError;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.internal.runners.model.ReflectiveCallable;
import org.junit.internal.runners.statements.ExpectException;
import org.junit.internal.runners.statements.Fail;
import org.junit.internal.runners.statements.RunAfters;
import org.junit.internal.runners.statements.RunBefores;
import org.junit.rules.RunRules;
import org.junit.rules.TestRule;
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
 * <p>You can use {@link com.facebook.buck.cxx.endtoend.CxxEndToEndTest} as an example of this
 * class' usage.
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
      EndToEndEnvironment environment = (EndToEndEnvironment) methodResult;
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
   * Marks validation errors if given method is not static, or does not return an {@link
   * EndToEndEnvironment}
   */
  private void validateEnvironmentMethod(
      FrameworkMethod environmentMethod, List<Throwable> errors) {
    if (!environmentMethod.isStatic()) {
      errors.add(
          new AnnotationFormatError(
              "Methods marked by @Environment or @EnvironmentFor must be static"));
    }
    if (!EndToEndEnvironment.class.isAssignableFrom(environmentMethod.getReturnType())) {
      errors.add(
          new AnnotationFormatError(
              "Methods marked by @Environment or @EnvironmentFor must return an EndToEndEnvironment"));
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
      FrameworkMethod environmentMethod = environmentMethods.get(0);
      validateEnvironmentMethod(environmentMethod, errors);
    }
  }

  /**
   * Marks validation errors in errors if a method marked by @EnvironmentFor points at a method that
   * does not exist or is not marked by @Test
   *
   * <p>Note: Adds an error to the list for each marked testName that doesn't exist
   */
  private void validateEnvironmentMapPointsToExistingTests(List<Throwable> errors) {
    List<FrameworkMethod> environmentForMethods =
        getTestClass().getAnnotatedMethods(EnvironmentFor.class);
    Set<String> testMethodNames =
        getTestClass()
            .getAnnotatedMethods(Test.class)
            .stream()
            .map(m -> m.getName())
            .collect(Collectors.toSet());
    for (FrameworkMethod environmentForMethod : environmentForMethods) {
      String[] environmentForTestNames =
          environmentForMethod.getAnnotation(EnvironmentFor.class).testNames();
      for (String testName : environmentForTestNames) {
        if (!testMethodNames.contains(testName)) {
          errors.add(
              new AnnotationFormatError(
                  String.format(
                      "EnvironmentFor method %s has testName %s which does not exist in the Test class"
                          + " (or it is not annotated with @Test)",
                      environmentForMethod.getName(), testName)));
        }
      }
    }
  }

  /**
   * Marks validation errors in errors if 2 methods marked by @EnvironmentFor contain the same test
   * names
   *
   * <p>Note: Adds an error to the list for each pair of duplicates found
   */
  private void validateEnvironmentMapContainsNoDuplicates(List<Throwable> errors) {
    List<FrameworkMethod> environmentForMethods =
        getTestClass().getAnnotatedMethods(EnvironmentFor.class);
    Map<String, String> seenTestNames = new HashMap<>();
    for (FrameworkMethod environmentForMethod : environmentForMethods) {
      String[] environmentForTestNames =
          environmentForMethod.getAnnotation(EnvironmentFor.class).testNames();
      for (String testName : environmentForTestNames) {
        if (seenTestNames.containsKey(testName)) {
          errors.add(
              new AnnotationFormatError(
                  String.format(
                      "EnvironmentFor methods %s and %s are both marked as for %s",
                      environmentForMethod.getName(), seenTestNames.get(testName), testName)));
        }
        seenTestNames.put(testName, environmentForMethod.getName());
      }
    }
  }

  /**
   * Marks validation errors in errors for each method marked by @EnvironmentFor does not return an
   * {@link EndToEndEnvironment} or is not static
   */
  private void validateEnvironmentMapMethods(List<Throwable> errors) {
    List<FrameworkMethod> environmentForMethods =
        getTestClass().getAnnotatedMethods(EnvironmentFor.class);
    for (FrameworkMethod environmentForMethod : environmentForMethods) {
      validateEnvironmentMethod(environmentForMethod, errors);
    }
  }

  /**
   * Marks validation errors in errors if:
   *
   * <ul>
   *   <li>Any method marked by @EnvironmentFor does not return an {@link EndToEndEnvironment}
   *   <li>Any method marked by @EnvironmentFor points at a method that does not exist or is not
   *       marked by @Test
   *   <li>2 methods marked by @EnvironmentFor contain the same test names
   * </ul>
   */
  private void validateEnvironmentMap(List<Throwable> errors) {
    validateEnvironmentMapMethods(errors);
    validateEnvironmentMapPointsToExistingTests(errors);
    validateEnvironmentMapContainsNoDuplicates(errors);
  }

  private void validateEnvironments(List<Throwable> errors) {
    validateDefaultEnvironment(errors);
    validateEnvironmentMap(errors);
  }

  /**
   * Marks validation errors in errors if the given method does not have exact args ({@link
   * EndToEndTestDescriptor}, {@link ProcessResult})
   */
  private void validateTestMethodArgs(FrameworkMethod testMethod, List<Throwable> errors) {
    Class<?>[] paramTypes = testMethod.getMethod().getParameterTypes();
    if (paramTypes.length != 2
        || !EndToEndTestDescriptor.class.isAssignableFrom(paramTypes[0])
        || !EndToEndWorkspace.class.isAssignableFrom(paramTypes[1])) {
      errors.add(
          new AnnotationFormatError(
              "Methods marked by @Test in the EndToEndRunner must support taking an "
                  + "EndToEndTestDescriptor and ProcessResult. Example:\n"
                  + "@Test\n"
                  + "public void shouldRun(EndToEndTestDescriptor test, EndToEndWorkspace workspace) {\n\n}"));
    }
  }

  private void validateTestAnnotations(List<Throwable> errors) {
    validatePublicVoidNoArgMethods(Before.class, false, errors);
    validatePublicVoidNoArgMethods(After.class, false, errors);
    RULE_VALIDATOR.validate(getTestClass(), errors);
    RULE_METHOD_VALIDATOR.validate(getTestClass(), errors);
  }

  /**
   * Marks validation errors in errors if:
   *
   * <ul>
   *   <li>Any method marked by @Test is a not a public non-static void method.
   *   <li>Any method marked by @Test does not have exact args ({@link EndToEndTestDescriptor},
   *       {@link ProcessResult})
   * </ul>
   */
  private void validateTestMethods(List<Throwable> errors) {
    List<FrameworkMethod> methods = getTestClass().getAnnotatedMethods(Test.class);

    for (FrameworkMethod testMethod : methods) {
      testMethod.validatePublicVoid(false, errors);
      validateTestMethodArgs(testMethod, errors);
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
          for (boolean buckdEnabled : testEnvironment.getBuckdToggled().getStates()) {
            for (Map<String, Map<String, String>> configs : testEnvironment.getLocalConfigSets()) {
              String command = testEnvironment.getCommand();
              String[] buildTargets = testEnvironment.getBuildTargets();
              String[] arguments = testEnvironment.getArguments();
              EndToEndTestDescriptor testDescriptor =
                  new EndToEndTestDescriptor(
                      testMethod,
                      templateSet,
                      command,
                      buildTargets,
                      arguments,
                      buckdEnabled,
                      variableMap,
                      configs);
              output.add(testDescriptor);
            }
          }
        }
      }
    }
    return output;
  }

  private Statement withBefores(Object target, Statement statement) {
    List<FrameworkMethod> befores = getTestClass().getAnnotatedMethods(Before.class);
    return befores.isEmpty() ? statement : new RunBefores(statement, befores, target);
  }

  private Statement withAfters(Object target, Statement statement) {
    List<FrameworkMethod> afters = getTestClass().getAnnotatedMethods(After.class);
    return afters.isEmpty() ? statement : new RunAfters(statement, afters, target);
  }

  private Statement withExpectedExceptions(EndToEndTestDescriptor child, Statement statement) {
    FrameworkMethod verificationMethod = child.getMethod();
    Test annotation = verificationMethod.getAnnotation(Test.class);
    Class<? extends Throwable> expectedException = annotation.expected();
    // ExpectException doesn't account for the default Test.None.class, so skip expecting an
    // exception if it is Test.None.class
    if (expectedException.isAssignableFrom(Test.None.class)) {
      return statement;
    }
    return new ExpectException(statement, expectedException);
  }

  private Statement withRules(EndToEndTestDescriptor child, Object target, Statement statement) {
    // We do not support MethodRules like the JUnit runner does as it has been functionally
    // replaced by TestRules (https://junit.org/junit4/javadoc/4.12/org/junit/rules/MethodRule.html)
    List<TestRule> testRules =
        getTestClass().getAnnotatedMethodValues(target, Rule.class, TestRule.class);
    testRules.addAll(getTestClass().getAnnotatedFieldValues(target, Rule.class, TestRule.class));
    return testRules.isEmpty()
        ? statement
        : new RunRules(statement, testRules, describeChild(child));
  }

  private Object createTest() throws Exception {
    return getTestClass().getOnlyConstructor().newInstance();
  }

  /** Creates the test statement for a testDescriptor */
  private Statement methodBlock(EndToEndTestDescriptor testDescriptor) {
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

    Statement statement = new BuckInvoker(testDescriptor, test);
    statement = withBefores(test, statement);
    statement = withAfters(test, statement);
    statement = withExpectedExceptions(testDescriptor, statement);
    statement = withRules(testDescriptor, test, statement);
    return statement;
  }

  @Override
  protected void collectInitializationErrors(List<Throwable> errors) {
    super.collectInitializationErrors(errors);
    validateTestMethods(errors);
    validateEnvironments(errors);
    validateTestAnnotations(errors);
  }

  @Override
  protected boolean isIgnored(EndToEndTestDescriptor child) {
    return child.getMethod().getAnnotation(Ignore.class) != null;
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
  protected void runChild(EndToEndTestDescriptor child, RunNotifier notifier) {
    Description description = describeChild(child);
    Statement statement;
    if (setupError != null) {
      statement = new Fail(setupError);
    } else if (isIgnored(child)) {
      notifier.fireTestIgnored(description);
      return;
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
