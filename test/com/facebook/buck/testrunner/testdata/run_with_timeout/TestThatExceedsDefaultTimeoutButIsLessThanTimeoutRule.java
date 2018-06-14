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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

@RunWith(NotBuckBlockJUnit4ClassRunner.class)
public class TestThatExceedsDefaultTimeoutButIsLessThanTimeoutRule {

  @Rule public Timeout timeout = new Timeout(/* millis */ 7000);

  /**
   * This test should take 5 seconds, which is longer than the default timeout specified in {@code
   * .buckconfig}, which is 3 seconds. However, the timeout specified by the {@link Timeout} (7
   * seconds) should take precedence, so this test should pass.
   */
  @Test
  public void testThatRunsForMoreThanThreeSecondsButForLessThanSevenSeconds() {
    try {
      Thread.sleep(/* millis */ 5 * 1000);
    } catch (InterruptedException e) {
      // Ignore.
    }
  }
}
