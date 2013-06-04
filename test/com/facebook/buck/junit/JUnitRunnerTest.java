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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Method;

import junit.framework.TestCase;

public class JUnitRunnerTest {

  @Test
  public void testIsJUnit4TestMethod() throws SecurityException, NoSuchMethodException {
    Method testGetterMethod = FakeJUnit4Test.class.getMethod("testGetter");
    assertTrue("Has @Test annotation, so should be considered a test method.",
        JUnitRunner.isTestMethod(testGetterMethod));

    Method testSetterMethod = FakeJUnit4Test.class.getMethod("testSetter");
    assertFalse("Does not have @Test annotation, so should not be considered a test method.",
        JUnitRunner.isTestMethod(testSetterMethod));

    Method ignoreableTestGetterMethod = FakeJUnit4Test.class.getMethod("testBehavior");
    assertFalse("Has @Ignore annotation, so should not be considered a test method.",
        JUnitRunner.isTestMethod(ignoreableTestGetterMethod));
  }

  @Test
  public void testIsJUnit3TestMethod() throws SecurityException, NoSuchMethodException {
    Method testGetterMethod = FakeJUnit3Test.class.getMethod("testGetter");
    assertTrue("Extends TestCase, so 'public void testXXX' methods are test methods.",
        JUnitRunner.isTestMethod(testGetterMethod));

    Method testSetterMethod = FakeJUnit3Test.class.getMethod("testSetter");
    assertTrue("Extends TestCase, so 'public void testXXX' methods are test methods.",
        JUnitRunner.isTestMethod(testSetterMethod));

    Method testSetterWithParamMethod = FakeJUnit3Test.class.getMethod("testSetter", String.class);
    assertFalse("Takes a parameter, so should not be considered a test method.",
        JUnitRunner.isTestMethod(testSetterWithParamMethod));

    Method testStringGetterMethod = FakeJUnit3Test.class.getMethod("testStringGetter");
    assertFalse("Has a return value, so should not be considered a test method.",
        JUnitRunner.isTestMethod(testStringGetterMethod));

    Method getMethod = FakeJUnit3Test.class.getMethod("get");
    assertFalse("Does not start with 'test', so should not be considered a test method.",
        JUnitRunner.isTestMethod(getMethod));

    Method ignoreableTestGetterMethod = FakeJUnit3Test.class.getMethod("testBehavior");
    assertFalse("Has @Ignore annotation, so should not be considered a test method.",
        JUnitRunner.isTestMethod(ignoreableTestGetterMethod));
  }

  public static class FakeJUnit4Test {

    @Test
    public void testGetter() {}

    public void testSetter() {}

    @Ignore
    @Test
    public void testBehavior() {}
  }

  public static class FakeJUnit3Test extends TestCase {

    @Test
    public void testGetter() {}

    public void testSetter() {}

    public void testSetter(@SuppressWarnings("unused") String s) {}

    public String testStringGetter() { return null; }

    public void get() {}

    @Ignore
    @Test
    public void testBehavior() {}
  }

}
