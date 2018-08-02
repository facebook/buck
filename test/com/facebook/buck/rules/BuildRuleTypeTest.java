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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.RuleType;
import org.junit.Test;

public class BuildRuleTypeTest {

  @Test(expected = NullPointerException.class)
  public void typeNamesMustNotBeNull() {
    RuleType.of(null, RuleType.Kind.BUILD);
  }

  @Test
  public void ruleNamesEndingWithUnderscoreTestAreTestRules() {
    assertFalse(RuleType.of("java_library", RuleType.Kind.BUILD).isTestRule());
    assertFalse(RuleType.of("genrule", RuleType.Kind.BUILD).isTestRule());

    // Does not end with _test
    assertFalse(RuleType.of("gentest", RuleType.Kind.BUILD).isTestRule());

    assertTrue(RuleType.of("java_test", RuleType.Kind.BUILD).isTestRule());
  }

  @Test
  public void equalityIsBasedOnNameAndKind() {
    assertEquals(RuleType.of("foo", RuleType.Kind.BUILD), RuleType.of("foo", RuleType.Kind.BUILD));
    assertNotEquals(
        RuleType.of("foo", RuleType.Kind.BUILD), RuleType.of("bar", RuleType.Kind.BUILD));
    assertNotEquals(
        RuleType.of("foo", RuleType.Kind.BUILD), RuleType.of("foo", RuleType.Kind.CONFIGURATION));
  }
}
