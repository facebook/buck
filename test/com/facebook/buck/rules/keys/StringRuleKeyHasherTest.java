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

import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.util.types.Pair;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class StringRuleKeyHasherTest {

  @RunWith(Parameterized.class)
  public static class UniquenessTest extends CommonRuleKeyHasherTest.UniquenessTest<String> {
    @SuppressWarnings("unchecked")
    @Parameters(name = "{0} != {2}")
    public static Iterable<Object[]> cases() {
      /** String hasher doesn't distinguish number types and that's fine. */
      return CommonRuleKeyHasherTest.uniquenessTestCases(
          StringRuleKeyHasherTest::newHasher,
          new Pair[] {new Pair<>("0", 0), new Pair<>("42", 42)});
    }
  }

  public static class ConsistencyTest extends CommonRuleKeyHasherTest.ConsistencyTest<String> {
    @Override
    protected RuleKeyHasher<String> newHasher() {
      return StringRuleKeyHasherTest.newHasher();
    }
  }

  public static class ExtraTests {
    @Test
    public void testStringEscaping() {
      assertEquals("string(\"abc\")::", newHasher().putString("abc").hash());
      assertEquals(
          "string(\"abc\\ndef\\\"g\\\"\")::", newHasher().putString("abc\ndef\"g\"").hash());
    }
  }

  public static StringRuleKeyHasher newHasher() {
    return new StringRuleKeyHasher();
  }
}
