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

import com.facebook.buck.intellij.ideabuck.actions.select.BuckKotlinTestClassDetectorTest.MockPotentialTestClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [BuckKotlinTestDetector] */
class BuckKotlinTestFunctionDetectorTest {

  @Test
  fun testSimpleClassNotATest() {
    val notATestClass = MockPotentialTestClass()
    val notATestFunction = MockPotentialTestFunction(containingClass = notATestClass)
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testAbstractClassNotATest() {
    val notATestClass = MockPotentialTestClass(isAbstract = true)
    val notATestFunction =
        MockPotentialTestFunction(containingClass = notATestClass, isAbstract = true)
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testJUnit3TestCase() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    val notATestFunction = MockPotentialTestFunction(containingClass = testClass)
    assertTrue(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }
  @Test
  fun testJUnit3TestCaseNotTestWrongFunctionName() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    val notATestFunction =
        MockPotentialTestFunction(
            containingClass = testClass, functionName = "doesn'tStartWithTest")
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testJUnit3TestCaseNotTestPrivateFunction() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    val notATestFunction = MockPotentialTestFunction(containingClass = testClass, isPublic = false)
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testJUnit3TestCaseNotTestAbstractFunction() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    val notATestFunction = MockPotentialTestFunction(containingClass = testClass, isAbstract = true)
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testJUnit3TestCaseNotTestFunctionHasParameters() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    val notATestFunction =
        MockPotentialTestFunction(containingClass = testClass, hasParameters = true)
    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testPrivateJUnit3TestCaseNotATest() {
    val notATestClass =
        MockPotentialTestClass(superClass = "junit.framework.TestCase", isPrivate = true)
    val notATestFunction = MockPotentialTestFunction(containingClass = notATestClass)

    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }
  @Test
  fun testNotAccessibleJUnit3TestCaseNotATest() {
    val notATestClass =
        MockPotentialTestClass(superClass = "junit.framework.TestCase", isData = true)
    val notATestFunction = MockPotentialTestFunction(containingClass = notATestClass)

    assertFalse(BuckKotlinTestDetector.isTestFunction(notATestFunction))
  }

  @Test
  fun testJUnit4TestFunctionWithTestAnnotation() {
    val plainClass = MockPotentialTestClass()
    val testFunction =
        MockPotentialTestFunction(
            containingClass = plainClass, functionAnnotations = listOf("org.junit.Test"))
    assertTrue(BuckKotlinTestDetector.isTestFunction(testFunction))
  }
  @Test
  fun testJUnit4TestFunctionWithParameters() {
    val plainClass = MockPotentialTestClass()
    val testFunction =
        MockPotentialTestFunction(
            containingClass = plainClass,
            hasParameters = true, // it's ok to have that in JUnit4
            functionAnnotations = listOf("org.junit.Test"))
    assertTrue(BuckKotlinTestDetector.isTestFunction(testFunction))
  }

  @Test
  fun testJUnit4FunctionWithRunWithAnnotations() {
    val testClass = MockPotentialTestClass(classAnnotations = listOf("org.junit.runner.RunWith"))
    val testFunction =
        MockPotentialTestFunction(
            containingClass = testClass, functionAnnotations = listOf("org.junit.Test"))
    assertTrue(BuckKotlinTestDetector.isTestFunction(testFunction))
  }

  @Test
  fun testJUnit4FunctionWithPartialAnnotations() {
    val testClass = MockPotentialTestClass(classAnnotations = listOf("org.junit.runner.RunWith"))
    val testFunction = MockPotentialTestFunction(containingClass = testClass) // missing @Test
    assertFalse(BuckKotlinTestDetector.isTestFunction(testFunction))
  }

  @Test
  fun testJUnit4TestFunctionWithNGAnnotation() {
    val testFunction =
        MockPotentialTestFunction(
            containingClass = MockPotentialTestClass(),
            functionAnnotations = listOf("org.testng.annotations.Test"))
    assertTrue(BuckKotlinTestDetector.isTestFunction(testFunction))
  }

  @Test
  fun testJUnit4TestAbstractFunctionWithNGAnnotation() {
    val testFunction =
        MockPotentialTestFunction(
            containingClass = MockPotentialTestClass(),
            isAbstract = true,
            functionAnnotations = listOf("org.testng.annotations.Test"))
    assertFalse(BuckKotlinTestDetector.isTestFunction(testFunction))
  }
}

class MockPotentialTestFunction(
    private val functionAnnotations: List<String> = emptyList(),
    private val functionName: String = "testJunit3Function",
    private val isAbstract: Boolean = false,
    private val isPublic: Boolean = true,
    private val hasReturnType: Boolean = false,
    private val hasParameters: Boolean = false,
    private val containingClass: PotentialTestClass
) : PotentialTestFunction {
  override fun getContainingClass(): PotentialTestClass? {
    return containingClass
  }

  override fun isPotentialTestFunction(): Boolean = !isAbstract

  override fun hasAnnotation(annotationName: String): Boolean =
      functionAnnotations.contains(annotationName)

  override fun isJUnit3TestMethod(): Boolean {
    return isPublic &&
        !isAbstract &&
        !hasReturnType &&
        !hasParameters &&
        functionName.startsWith("test")
  }
}
