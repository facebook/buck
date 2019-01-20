/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.testng.Assert.assertEquals;

import java.util.logging.Logger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class TestNGParametersTest {

  private static final Logger LOG = Logger.getLogger("LoggingTest");

  @DataProvider
  public Object[][] passingParams() {
    return new Object[][] {
      {0, 0},
      {1, 1},
      {2, 4},
      {3, 9},
    };
  }

  @DataProvider
  public Object[][] failingParams() {
    return new Object[][] {
      {0, 0},
      {1, 2},
      {2, 3},
      {3, 4},
    };
  }

  @Test(dataProvider = "passingParams")
  public void passingTestWithParams(int n, int nsquared) {
    assertEquals(n * n, nsquared, "nsquared = n*n");
  }

  @Test(dataProvider = "failingParams")
  public void failingTestWithParams(int n, int nsquared) {
    assertEquals(n * n, nsquared, "nsquared = n*n");
  }
}
