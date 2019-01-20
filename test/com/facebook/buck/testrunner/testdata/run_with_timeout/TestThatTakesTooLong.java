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

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(NotBuckBlockJUnit4ClassRunner.class)
public class TestThatTakesTooLong {

  /**
   * If the default timeout in {@code .buckconfig} is set to 500 milliseconds, as expected, then
   * this test should fail due to a timeout.
   */
  @Test
  public void testShouldBlockForSixSeconds() {
    try {
      Thread.sleep(/* millis */ 30 * 1000);
    } catch (InterruptedException e) {
      // Ignore.
    }
  }
}
