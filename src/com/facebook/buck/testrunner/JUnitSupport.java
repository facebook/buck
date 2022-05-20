/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testrunner;

import static com.facebook.buck.testrunner.CheckDependency.optionalAnnotation;
import static com.facebook.buck.testrunner.CheckDependency.optionalClass;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.platform.commons.util.ReflectionUtils;

/** Validate the presence of JUnit APIs. */
class JUnitSupport {

  private static final JUnit4ApiSupport JUNIT4_API = new JUnit4ApiSupport();
  private static final JupiterApiSupport JUPITER_API = new JupiterApiSupport();

  static JUnit4ApiSupport junit4Api() {
    return JUNIT4_API;
  }

  static JupiterApiSupport jupiterApi() {
    return JUPITER_API;
  }

  /** Validates if a JUnit Engine is present and if a given class it's compatible. */
  interface ApiSupport extends Predicate<Class<?>> {

    /** @return true if the API is present into the classpath, false otherwise. */
    boolean enabled();

    /** @return true if the element is disabled. */
    boolean disabled(AnnotatedElement element);

    /**
     * @param clazz Class or Method to be verified.
     * @return true if it's a valid class or method that represents a unit test.
     */
    @Override
    default boolean test(Class<?> clazz) {
      return enabled() && isTestClass(clazz);
    }

    /**
     * @param clazz Class or Method to be verified.
     * @return true if it's a valid class or method that represents a unit test.
     */
    boolean isTestClass(Class<?> clazz);
  }

  /**
   * Validate if Jupiter API is present and if a class is a valid candidate to be a test class.
   *
   * <p>It checks for Optional annotations such as ParameterizedTest, without failing. The
   * implementation use Optional classes and annotations to prevent {@link ClassNotFoundException}
   * in case the API is not available.
   */
  static class JupiterApiSupport implements ApiSupport {

    static final Optional<Class<? extends Annotation>> NESTED_ANN =
        optionalAnnotation("org.junit.jupiter.api.Nested");
    static final Optional<Class<? extends Annotation>> DISABLED_ANN =
        optionalAnnotation("org.junit.jupiter.api.Disabled");

    static final List<Class<? extends Annotation>> METHOD_ANNOTATIONS;

    static {
      METHOD_ANNOTATIONS =
          Stream.of(
                  optionalAnnotation("org.junit.jupiter.api.Test"),
                  optionalAnnotation("org.junit.jupiter.api.TestFactory"),
                  optionalAnnotation("org.junit.jupiter.api.RepeatedTest"),
                  optionalAnnotation("org.junit.jupiter.params.ParameterizedTest"))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .collect(Collectors.toList());
    }

    @Override
    public boolean enabled() {
      return NESTED_ANN.isPresent();
    }

    @Override
    public boolean disabled(AnnotatedElement element) {
      return DISABLED_ANN.filter(element::isAnnotationPresent).isPresent();
    }

    @Override
    public boolean isTestClass(Class<?> clazz) {
      List<Method> testMethods = ReflectionUtils.findMethods(clazz, this::isTestMethod);
      if (!testMethods.isEmpty()) {
        return true;
      }
      for (Class<?> nestedClass : clazz.getDeclaredClasses()) {
        if (NESTED_ANN.filter(nestedClass::isAnnotationPresent).isPresent() && test(nestedClass)) {
          return true;
        }
      }
      return false;
    }

    private boolean isTestMethod(Method m) {
      return ReflectionUtils.isNotPrivate(m)
          && METHOD_ANNOTATIONS.stream().anyMatch(m::isAnnotationPresent);
    }
  }

  /**
   * Backward compatibility with JUnitRunner.mightBeATestClass
   *
   * <p>Validate if JUnit4 API is present and if a class is a valid candidate to be a test class.
   * The implementation use Optional classes and annotations to prevent {@link
   * ClassNotFoundException} in case the API is not available.
   */
  static class JUnit4ApiSupport implements ApiSupport {

    static final Optional<Class<? extends Annotation>> IGNORE_ANN =
        optionalAnnotation("org.junit.Ignore");
    static final Optional<Class<? extends Annotation>> TEST_ANN =
        optionalAnnotation("org.junit.Test");
    static final Optional<Class<? extends Annotation>> RUN_WITH_ANN =
        optionalAnnotation("org.junit.runner.RunWith");
    static final Optional<Class<?>> TEST_CASE_CLASS = optionalClass("junit.framework.TestCase");
    static final Optional<Class<?>> ASSUMPTION_EXCEPTION_CLASS =
        optionalClass("org.junit.internal.AssumptionViolatedException");

    @Override
    public boolean enabled() {
      return TEST_ANN.isPresent();
    }

    @Override
    public boolean disabled(AnnotatedElement element) {
      return IGNORE_ANN.filter(element::isAnnotationPresent).isPresent();
    }

    @Override
    public boolean isTestClass(Class<?> clazz) {
      if (RUN_WITH_ANN.filter(clazz::isAnnotationPresent).isPresent()) {
        return true;
      }
      // Since no RunWith annotation, using standard runner, which requires
      // test classes to be non-abstract/non-interface
      int klassModifiers = clazz.getModifiers();
      if (Modifier.isInterface(klassModifiers) || Modifier.isAbstract(klassModifiers)) {
        return false;
      }

      // Classes that extend junit.framework.TestCase are JUnit3-style test classes.
      if (TEST_CASE_CLASS.filter(testCase -> testCase.isAssignableFrom(clazz)).isPresent()) {
        return true;
      }

      // Since no RunWith annotation, using standard runner, which requires
      // test classes to have exactly one public constructor (that has no args).
      // Classes may have (non-public) constructors (with or without args).
      boolean foundPublicNoArgConstructor = false;
      for (Constructor<?> c : clazz.getConstructors()) {
        if (Modifier.isPublic(c.getModifiers())) {
          if (c.getParameterCount() != 0) {
            return false;
          }
          foundPublicNoArgConstructor = true;
        }
      }
      if (!foundPublicNoArgConstructor) {
        return false;
      }
      // If the class has a JUnit4 @Test-annotated method, it's a test class.
      boolean hasAtLeastOneTest = false;
      for (Method m : clazz.getMethods()) {
        if (isTestMethod(m)) {
          hasAtLeastOneTest = true;
          break;
        }
      }
      return hasAtLeastOneTest;
    }

    private boolean isTestMethod(Method m) {
      return Modifier.isPublic(m.getModifiers())
          && m.getParameterCount() == 0
          && TEST_ANN.filter(m::isAnnotationPresent).isPresent();
    }

    /**
     * @param throwable to be checked
     * @return true if the error has internal Assumption as its super class.
     */
    public boolean isAssumption(Throwable throwable) {
      return throwable != null
          && ASSUMPTION_EXCEPTION_CLASS
              .filter(testCase -> testCase.isAssignableFrom(throwable.getClass()))
              .isPresent();
    }
  }
}
