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

package com.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This test verifies that all junit before/after functions gets called in correct
 * order: @BeforeClass - oneTimeSetUp @Before - setUp @Test - testSomething(1 or 2) @After -
 * tearDown @Before - setUp @Test - testSomething(1 or 2) @After - tearDown @AfterClass -
 * oneTimeTearDown
 *
 * <p>JUnit don't fail a test when @AfterClass or @BeforeClass methods throw an Exception so making
 * assertions within those methods doesn't make sense. Instead we call System.exit from @AfterClass
 * and will verify if test fails due to invalid return code.
 */
public class CallFlowTest {

  private static int beforeClassCallCounter;
  private static boolean afterClassCalled;
  private static boolean isWithinBeforeAndAfter;
  private static int testCallCounter;

  @BeforeClass
  public static void oneTimeSetUp() {
    beforeClassCallCounter++;
  }

  @Before
  public void setUp() {
    assertEquals(1, beforeClassCallCounter);
    assertFalse(isWithinBeforeAndAfter);
    isWithinBeforeAndAfter = true;
  }

  @After
  public void tearDown() {
    assertTrue(isWithinBeforeAndAfter);
    isWithinBeforeAndAfter = false;
  }

  @AfterClass
  public static void oneTimeTearDown() throws Exception {
    if (!afterClassCalled
        && beforeClassCallCounter == 1
        && testCallCounter == 2
        && !isWithinBeforeAndAfter) {
      System.exit(42);
    }
    afterClassCalled = true;
  }

  private void something() {
    assertTrue(isWithinBeforeAndAfter);
    testCallCounter++;
  }

  @Test
  public void testSomething1() {
    something();
  }

  @Test
  public void testSomething2() {
    something();
  }
}
