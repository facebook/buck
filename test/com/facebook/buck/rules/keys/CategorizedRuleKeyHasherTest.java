/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.RuleKeyFieldCategory;
import com.google.common.hash.HashCode;
import org.junit.Test;

public class CategorizedRuleKeyHasherTest extends AbstractRuleKeyHasherTest<HashCode> {

  @Test
  public void testCategories() {
    // Values hashed under a particular category affect only the portion of rulekey dedicated to
    // that specific category.
    assertEquals(
        HashCode.fromString("533999ab0000000000000000000000000000000000000000"),
        newHasher().selectCategory(RuleKeyFieldCategory.UNKNOWN).putString("value1").hash());
    assertEquals(
        HashCode.fromString("00000000743999ab00000000000000000000000000000000"),
        newHasher().selectCategory(RuleKeyFieldCategory.SOURCE).putString("value2").hash());
    assertEquals(
        HashCode.fromString("0000000000000000953999ab000000000000000000000000"),
        newHasher().selectCategory(RuleKeyFieldCategory.DEPENDENCY).putString("value3").hash());
    assertEquals(
        HashCode.fromString("000000000000000000000000b63999ab0000000000000000"),
        newHasher().selectCategory(RuleKeyFieldCategory.PARAMETER).putString("value4").hash());
    assertEquals(
        HashCode.fromString("00000000000000000000000000000000d73999ab00000000"),
        newHasher().selectCategory(RuleKeyFieldCategory.ENVIRONMENT).putString("value5").hash());
    assertEquals(
        HashCode.fromString("0000000000000000000000000000000000000000f83999ab"),
        newHasher().selectCategory(RuleKeyFieldCategory.TOOL).putString("value6").hash());

    assertEquals(
        HashCode.fromString("533999ab743999ab953999abb63999abd73999abf83999ab"),
        newHasher()
            .selectCategory(RuleKeyFieldCategory.UNKNOWN)
            .putString("value1")
            .selectCategory(RuleKeyFieldCategory.SOURCE)
            .putString("value2")
            .selectCategory(RuleKeyFieldCategory.DEPENDENCY)
            .putString("value3")
            .selectCategory(RuleKeyFieldCategory.PARAMETER)
            .putString("value4")
            .selectCategory(RuleKeyFieldCategory.ENVIRONMENT)
            .putString("value5")
            .selectCategory(RuleKeyFieldCategory.TOOL)
            .putString("value6")
            .hash());
  }

  @Override
  protected RuleKeyHasher<HashCode> newHasher() {
    return new CategorizedRuleKeyHasher();
  }
}
