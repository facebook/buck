/*
 * Copyright 2013-present Facebook, Inc.
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

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ParametrizedTest {

  private int number;

  public ParametrizedTest(int number) {
    this.number = number;
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    Object[][] data = new Object[][] {{0}, {1}, {2}, {3}};
    return Arrays.asList(data);
  }

  @Test
  public void parametrizedTest() {
    System.out.print(String.format("Parameter: %d\n", number));
  }
}
