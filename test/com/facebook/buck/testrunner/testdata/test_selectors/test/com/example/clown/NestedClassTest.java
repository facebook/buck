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

package com.example.clown;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

@RunWith(Enclosed.class)
public class NestedClassTest {

  public static class FirstInnerTest {
    @Test
    public void runMe() {
      Assert.assertTrue("Oh no, zero isn't zero! :O", 0 == 0);
    }
  }

  public static class OtherInnerTest {
    @Test
    public void runMe() {
      Assert.assertFalse("Oh no, one is zero! :O", 1 == 0);
    }
  }

  @Test
  public void outerTest() {
    Assert.assertTrue("Oh no, true isn't true! :O", true == true);
  }
}
