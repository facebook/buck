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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuildRuleTypeTest {

  @Test(expected = NullPointerException.class)
  public void typeNamesMustNotBeNull() {
    BuildRuleType.of(null);
  }

  @Test
  public void ruleNamesEndingWithUnderscoreTestAreTestRules() {
    assertFalse(BuildRuleType.of("java_library").isTestRule());
    assertFalse(BuildRuleType.of("genrule").isTestRule());

    // Does not end with _test
    assertFalse(BuildRuleType.of("gentest").isTestRule());

    assertTrue(BuildRuleType.of("java_test").isTestRule());
  }

  @Test
  public void equalityIsBasedOnName() {
    assertEquals(BuildRuleType.of("foo"), BuildRuleType.of("foo"));
  }
}
