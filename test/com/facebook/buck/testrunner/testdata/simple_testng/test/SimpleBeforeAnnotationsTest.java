/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static org.testng.Assert.assertNotNull;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

@Test
public class SimpleBeforeAnnotationsTest {

  Long beforeMethodValue;
  Long beforeClassValue;
  Long beforeTestValue;

  @BeforeMethod
  public void beforeMethod() {
    beforeMethodValue = System.currentTimeMillis();
  }

  @BeforeClass
  public void beforeClass() {
    beforeClassValue = System.currentTimeMillis();
  }

  @BeforeTest
  public void beforeTest() {
    beforeTestValue = System.currentTimeMillis();
  }

  @Test
  public void beforeAnnotationsTest() {
    assertNotNull(beforeClassValue);
    assertNotNull(beforeTestValue);
    assertNotNull(beforeMethodValue);
  }
}
