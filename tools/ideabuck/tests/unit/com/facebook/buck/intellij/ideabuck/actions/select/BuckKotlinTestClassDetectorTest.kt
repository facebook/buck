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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [BuckKotlinTestDetector] */
class BuckKotlinTestClassDetectorTest {

  @Test
  fun testAbstractClassNotATest() {
    val notATestClass = MockPotentialTestClass(isAbstract = true)
    assertFalse(BuckKotlinTestDetector.isTestClass(notATestClass))
  }

  @Test
  fun testSimpleClassNotATest() {
    val notATestClass = MockPotentialTestClass()
    assertFalse(BuckKotlinTestDetector.isTestClass(notATestClass))
  }

  @Test
  fun testJUnit3TestCase() {
    val testClass = MockPotentialTestClass(superClass = "junit.framework.TestCase")
    assertTrue(BuckKotlinTestDetector.isTestClass(testClass))
  }
  @Test
  fun testPrivateJUnit3TestCaseNotATest() {
    val notATestClass =
        MockPotentialTestClass(superClass = "junit.framework.TestCase", isPrivate = true)
    assertFalse(BuckKotlinTestDetector.isTestClass(notATestClass))
  }
  @Test
  fun testAbstractJUnit3TestCaseNotATest() {
    val notATestClass =
        MockPotentialTestClass(superClass = "junit.framework.TestCase", isAbstract = true)
    assertFalse(BuckKotlinTestDetector.isTestClass(notATestClass))
  }

  @Test
  fun testNotAccessibleJUnit3TestCaseNotATest() {
    val notATestClass =
        MockPotentialTestClass(superClass = "junit.framework.TestCase", isData = true)
    assertFalse(BuckKotlinTestDetector.isTestClass(notATestClass))
  }

  @Test
  fun testJUnit4TestClassWithJUnitTestFunctionAnnotated() {
    val testClass = MockPotentialTestClass(functionAnnotations = listOf("org.junit.Test"))
    assertTrue(BuckKotlinTestDetector.isTestClass(testClass))
  }

  @Test
  fun testJUnit4ClassWithRunWithAnnotation() {
    val testClass = MockPotentialTestClass(classAnnotations = listOf("org.junit.runner.RunWith"))
    assertTrue(BuckKotlinTestDetector.isTestClass(testClass))
  }

  @Test
  fun testJUnit4TestClassWithNGTestFunctionAnnotated() {
    val testClass =
        MockPotentialTestClass(functionAnnotations = listOf("org.testng.annotations.Test"))
    assertTrue(BuckKotlinTestDetector.isTestClass(testClass))
  }

  class MockPotentialTestClass(
      private val superClass: String = "",
      private val functionAnnotations: List<String> = emptyList(),
      private val classAnnotations: List<String> = emptyList(),
      private val isAbstract: Boolean = false,
      private val isPrivate: Boolean = false,
      private val isData: Boolean = false
  ) : PotentialTestClass {

    override fun hasSuperClass(superClassQualifiedName: String): Boolean {
      return superClass == superClassQualifiedName
    }

    override fun hasTestFunction(annotationName: String): Boolean {
      return functionAnnotations.contains(annotationName)
    }

    override fun hasAnnotation(annotationName: String): Boolean {
      return classAnnotations.contains(annotationName)
    }

    override fun isPotentialTestClass(): Boolean {
      return !isAbstract && !isPrivate && !isData
    }
  }
}
