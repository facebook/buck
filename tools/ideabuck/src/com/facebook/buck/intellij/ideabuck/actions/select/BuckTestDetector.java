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
package com.facebook.buck.intellij.ideabuck.actions.select;

import com.intellij.lang.jvm.JvmModifier;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import java.util.stream.Stream;

/**
 * Utility methods to detect tests that Buck can run in {@link PsiMethod} and {@link PsiClass}
 * elements.
 *
 * <p>Currently, these recognize JUnit3, JUnit4, and TestNG test classes and methods.
 */
public class BuckTestDetector {

  /**
   * Returns true if the given class is one of the kind of tests that Buck knows how to run.
   * (Currently, JUnit3, JUnit4, and TestNG.)
   *
   * <p>Note that this is merely a syntactic check that makes no guarantee that the class appears in
   * a file that is part of a buck test target (or even a buck cell).
   */
  public static boolean isTestClass(PsiClass psiClass) {
    return isJUnit3TestCaseClass(psiClass)
        || isAnnotatedJUnit4TestClass(psiClass)
        || isJUnit4TestClassWithAtLeastOneTestMethod(psiClass)
        || isTestNGTestClassWithAtLeastOneTestMethod(psiClass);
  }

  /**
   * Returns true if the given method is one of the kind of tests that Buck knows how to run.
   * (Currently, JUnit3, JUnit4, and TestNG.)
   *
   * <p>Note that this is merely a syntactic check that makes no guarantee that the method appears
   * in a file that is part of a buck test target (or even a buck cell).
   */
  public static boolean isTestMethod(PsiMethod psiMethod) {
    PsiClass psiClass = psiMethod.getContainingClass();
    if (psiClass == null) {
      return false;
    }
    if (isJUnit3TestCaseClass(psiClass)) {
      return isJUnit3TestMethod(psiMethod);
    }
    if (isAnnotatedJUnit4TestClass(psiClass)) {
      return isJUnit4TestMethod(psiMethod);
    }
    if (isPotentialJUnit4TestClass(psiClass) && isJUnit4TestMethod(psiMethod)) {
      return true;
    }
    if (isPotentialTestNGTestClass(psiClass) && isTestNGTestMethod(psiMethod)) {
      return true;
    }
    return false;
  }

  private static boolean isJUnit3TestCaseClass(PsiClass psiClass) {
    if (psiClass.hasModifier(JvmModifier.PUBLIC) && !psiClass.hasModifier(JvmModifier.ABSTRACT)) {
      PsiClass superClass = psiClass.getSuperClass();
      while (superClass != null) {
        if ("junit.framework.TestCase".equals(superClass.getQualifiedName())) {
          return true;
        }
        superClass = superClass.getSuperClass();
      }
    }
    return false;
  }

  private static boolean isAnnotatedJUnit4TestClass(PsiClass psiClass) {
    return isPotentialJUnit4TestClass(psiClass)
        && hasAnnotation(psiClass, "org.junit.runner.RunWith");
  }

  private static boolean isJUnit4TestClassWithAtLeastOneTestMethod(PsiClass psiClass) {
    return isPotentialJUnit4TestClass(psiClass)
        && Stream.of(psiClass.getAllMethods()).anyMatch(BuckTestDetector::isJUnit4TestMethod);
  }

  private static boolean isPotentialJUnit4TestClass(PsiClass psiClass) {
    return psiClass.hasModifier(JvmModifier.PUBLIC) && !psiClass.hasModifier(JvmModifier.ABSTRACT);
  }

  private static boolean isTestNGTestClassWithAtLeastOneTestMethod(PsiClass psiClass) {
    return isPotentialTestNGTestClass(psiClass)
        && Stream.of(psiClass.getAllMethods()).anyMatch(BuckTestDetector::isTestNGTestMethod);
  }

  private static boolean isPotentialTestNGTestClass(PsiClass psiClass) {
    return psiClass.hasModifier(JvmModifier.PUBLIC) && !psiClass.hasModifier(JvmModifier.ABSTRACT);
  }

  private static boolean isJUnit3TestMethod(PsiMethod psiMethod) {
    return psiMethod.hasModifier(JvmModifier.PUBLIC)
        && !psiMethod.hasModifier(JvmModifier.STATIC)
        && !psiMethod.hasModifier(JvmModifier.ABSTRACT)
        && PsiType.VOID.equals(psiMethod.getReturnType())
        && psiMethod.getName().startsWith("test")
        && psiMethod.getParameterList().isEmpty();
  }

  private static boolean isJUnit4TestMethod(PsiMethod psiMethod) {
    return hasAnnotation(psiMethod, "org.junit.Test")
        && !psiMethod.hasModifier(JvmModifier.ABSTRACT);
  }

  private static boolean isTestNGTestMethod(PsiMethod psiMethod) {
    return hasAnnotation(psiMethod, "org.testng.annotations.Test")
        && !psiMethod.hasModifier(JvmModifier.ABSTRACT);
  }

  private static boolean hasAnnotation(PsiClass psiClass, String fqn) {
    return Stream.of(psiClass.getAnnotations())
        .anyMatch(psiAnnotation -> fqn.equals(psiAnnotation.getQualifiedName()));
  }

  private static boolean hasAnnotation(PsiMethod psiMethod, String fqn) {
    return Stream.of(psiMethod.getAnnotations())
        .anyMatch(psiAnnotation -> fqn.equals(psiAnnotation.getQualifiedName()));
  }
}
