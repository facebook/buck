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

import static org.junit.Assert.*;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase;
import junit.framework.AssertionFailedError;

public class BuckTestDetectorTest extends LightCodeInsightFixtureTestCase {

  private static String join(String... lines) {
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line).append("\n");
    }
    return sb.toString();
  }

  PsiClass getPsiClass(PsiFile psiFile, String className) {
    for (PsiClass psiClass : PsiTreeUtil.findChildrenOfType(psiFile, PsiClass.class)) {
      if (className.equals(psiClass.getQualifiedName())) {
        return psiClass;
      }
    }
    throw new AssertionFailedError(
        "Could not find class " + className + " in file " + psiFile.getName());
  }

  PsiMethod getPsiMethod(PsiFile psiFile, String methodName) {
    for (PsiMethod psiMethod : PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class)) {
      if (methodName.equals(psiMethod.getName())) {
        return psiMethod;
      }
    }
    throw new AssertionFailedError(
        "Could not find method " + methodName + " in file " + psiFile.getName());
  }

  void assertIsTestClass(PsiFile psiFile, String className) {
    PsiClass psiClass = getPsiClass(psiFile, className);
    assertNotNull("Should be able to find class " + className, psiClass);
    assertTrue(className + " should be a test class", BuckTestDetector.isTestClass(psiClass));
  }

  void assertIsNotTestClass(PsiFile psiFile, String className) {
    PsiClass psiClass = getPsiClass(psiFile, className);
    assertNotNull("Should be able to find class " + className, psiClass);
    assertFalse(className + " should not be a test class", BuckTestDetector.isTestClass(psiClass));
  }

  void assertIsTestMethod(PsiFile psiFile, String methodName) {
    PsiMethod psiMethod = getPsiMethod(psiFile, methodName);
    assertNotNull("Should be able to find Method " + methodName, psiMethod);
    assertTrue(methodName + " should be a test method", BuckTestDetector.isTestMethod(psiMethod));
  }

  void assertIsNotTestMethod(PsiFile psiFile, String methodName) {
    PsiMethod psiMethod = getPsiMethod(psiFile, methodName);
    assertNotNull("Should be able to find method " + methodName, psiMethod);
    assertFalse(
        methodName + " should not be a test method", BuckTestDetector.isTestMethod(psiMethod));
  }

  public void testNonTestClass() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "NotATest.java",
            join(
                "// Just because it has 'Test' in the name doesn't make it a test",
                "public class TestNotATest {",
                "    public void setUp() {",
                "    }",
                "    public void testNotATestMethod() {",
                "    }",
                "}"));
    assertIsNotTestClass(psiFile, "TestNotATest");
    assertIsNotTestMethod(psiFile, "setUp");
    assertIsNotTestMethod(psiFile, "testNotATestMethod");
  }

  public void testJUnit3TestCase() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "public class ClassName extends junit.framework.TestCase {",
                "    public void setUp() {}",
                "    public void testFoo() {}",
                "    public void testBar() {}",
                "    void testNonPublic() {}",
                "    public int testReturnValue() {}",
                "    public void testHasArgs(int notAllowedInJUnit3) {}",
                "    public abstract void testAbstract();",
                "}"));
    assertIsTestClass(psiFile, "ClassName");
    assertIsNotTestMethod(psiFile, "setUp");
    assertIsTestMethod(psiFile, "testFoo");
    assertIsTestMethod(psiFile, "testBar");
    assertIsNotTestMethod(psiFile, "testNonPublic");
    assertIsNotTestMethod(psiFile, "testReturnValue");
    assertIsNotTestMethod(psiFile, "testHasArgs");
    assertIsNotTestMethod(psiFile, "testAbstract");
  }

  public void testAbstractJUnit3Test() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "public abstract class ClassName extends junit.framework.TestCase {",
                "    public void testFoo() {}",
                "}"));
    assertIsNotTestClass(psiFile, "ClassName");
    assertIsNotTestMethod(psiFile, "testFoo");
  }

  public void testNonPublicJUnit3Test() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "class ClassName extends junit.framework.TestCase {",
                "    public void testFoo() {}",
                "}"));
    assertIsNotTestClass(psiFile, "ClassName");
    assertIsNotTestMethod(psiFile, "testFoo");
  }

  public void testJUnit3TestBasedOnSubClass() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    myFixture.addClass("public class BaseTestCase extends junit.framework.TestCase {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "public class ClassName extends BaseTestCase {",
                "    public void testFoo() {}",
                "}"));
    assertIsTestClass(psiFile, "ClassName");
    assertIsTestMethod(psiFile, "testFoo");
  }

  public void testJUnit4Tests() {
    myFixture.addClass("package org.junit; public @interface Test {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "import org.junit.Test;",
                "public class ClassName {",
                "    public void testInNameButNotATest() {}",
                "    @Test",
                "    public void fooMethod() {}",
                "    @org.junit.Test",
                "    public void barMethod(int args, boolean are, String ok) {}",
                "}"));
    assertIsTestClass(psiFile, "ClassName");
    assertIsNotTestMethod(psiFile, "testInNameButNotATest");
    assertIsTestMethod(psiFile, "fooMethod");
    assertIsTestMethod(psiFile, "barMethod");
  }

  public void testJUnit4ClassWithRunWithAnnotation() {
    myFixture.addClass("package org.junit.runner; public @interface RunWith {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java", join("@org.junit.runner.RunWith()", "public class ClassName {}"));
    assertIsTestClass(psiFile, "ClassName");
  }

  public void testTestNGTests() {
    myFixture.addClass("package org.testng.annotations; public @interface Test {}");
    PsiFile psiFile =
        myFixture.configureByText(
            "ClassName.java",
            join(
                "import org.testng.annotations.Test;",
                "public class ClassName {",
                "    public void testInNameButNotATest() {}",
                "    @Test",
                "    public void fooMethod() {}",
                "    @org.testng.annotations.Test",
                "    public void barMethod(int args, boolean are, String ok) {}",
                "}"));
    assertIsTestClass(psiFile, "ClassName");
    assertIsNotTestMethod(psiFile, "testInNameButNotATest");
    assertIsTestMethod(psiFile, "fooMethod");
    assertIsTestMethod(psiFile, "barMethod");
  }
}
