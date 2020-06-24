/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.intellij.ideabuck.actions.select

/**
 * Utility functions to detect tests that Buck can run in {@link KtNamedFunction} and {@link
 * KtClass} elements.
 *
 * <p>Currently, these recognize JUnit3, JUnit4, and TestNG test classes and methods.
 */
class BuckKotlinTestDetector {

  companion object {

    /**
     * Returns true if the given class is one of the kind of tests that Buck knows how to run.
     * (Currently, JUnit3, JUnit4, and TestNG.)
     *
     * <p>Note that this is merely a syntactic check that makes no guarantee that the class appears
     * in a file that is part of a buck test target (or even a buck cell).
     */
    fun isTestClass(maybeTestClass: PotentialTestClass): Boolean {
      // check that class is accessible

      if (maybeTestClass.isPotentialTestClass()) {
        return isAnnotatedJUnit4TestClass(maybeTestClass) ||
            isJUnit3TestCaseClass(maybeTestClass) ||
            isJUnit4TestClassWithAtLeastOneTestMethod(maybeTestClass) ||
            isTestNGTestClassWithAtLeastOneTestMethod(maybeTestClass)
      }
      return false
    }

    /**
     * Returns true if the given method is one of the kind of tests that Buck knows how to run.
     * (Currently, JUnit3, JUnit4, and TestNG.)
     *
     * <p>Note that this is merely a syntactic check that makes no guarantee that the method appears
     * in a file that is part of a buck test target (or even a buck cell).
     */
    fun isTestFunction(function: PotentialTestFunction): Boolean {
      val containingClass = function.getContainingClass() ?: return false
      if (containingClass.isPotentialTestClass()) {
        return when {
          isAnnotatedJUnit4TestClass(containingClass) -> isJUnit4TestMethod(function)
          isJUnit3TestCaseClass(containingClass) -> isJUnit3TestMethod(function)
          else -> isJUnit4TestMethod(function) || isTestNGTestMethod(function)
        }
      }
      return false
    }

    private fun isTestNGTestClassWithAtLeastOneTestMethod(testClass: PotentialTestClass): Boolean =
        testClass.hasTestFunction("org.testng.annotations.Test")

    private fun isJUnit4TestClassWithAtLeastOneTestMethod(testClass: PotentialTestClass): Boolean =
        testClass.hasTestFunction("org.junit.Test")

    private fun isAnnotatedJUnit4TestClass(testClass: PotentialTestClass): Boolean =
        testClass.hasAnnotation("org.junit.runner.RunWith")

    private fun isJUnit3TestCaseClass(testClass: PotentialTestClass): Boolean =
        testClass.hasSuperClass("junit.framework.TestCase")

    private fun isJUnit3TestMethod(function: PotentialTestFunction): Boolean =
        function.isJUnit3TestMethod()

    private fun isJUnit4TestMethod(function: PotentialTestFunction): Boolean =
        function.isPotentialTestFunction() && function.hasAnnotation("org.junit.Test")

    private fun isTestNGTestMethod(function: PotentialTestFunction): Boolean =
        function.isPotentialTestFunction() && function.hasAnnotation("org.testng.annotations.Test")
  }
}

/** Represent potential test class without dependency on PsiElements */
interface PotentialTestClass {
  fun isPotentialTestClass(): Boolean
  fun hasTestFunction(annotationName: String): Boolean
  fun hasAnnotation(annotationName: String): Boolean
  fun hasSuperClass(superClassQualifiedName: String): Boolean
}

/** Represent potential test function without dependency on PsiElements */
interface PotentialTestFunction {
  fun isPotentialTestFunction(): Boolean
  fun getContainingClass(): PotentialTestClass?
  fun hasAnnotation(annotationName: String): Boolean
  fun isJUnit3TestMethod(): Boolean
}
