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

import org.junit.Test;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class StringRuleKeyHasherTest extends AbstractRuleKeyHasherTest<String> {

  @Test
  public void testStringEscaping() {
    assertEquals("string(\"abc\")::", newHasher().putString("abc").hash());
    assertEquals("string(\"abc\\ndef\\\"g\\\"\")::", newHasher().putString("abc\ndef\"g\"").hash());
  }

  /** String hasher doesn't distinguish number types and that's fine. */
  @Override
  protected Number[] getNumbersForUniquenessTest() {
    return new Number[] {0, 42};
  }

  @Override
  protected StringRuleKeyHasher newHasher() {
    return new StringRuleKeyHasher();
  }
}
