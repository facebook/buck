/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.keys.hasher.GuavaRuleKeyHasher;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class GuavaRuleKeyHasherTest {

  @RunWith(Parameterized.class)
  public static class UniquenessTest extends CommonRuleKeyHasherTest.UniquenessTest<HashCode> {
    @Parameters(name = "{0} != {2}")
    public static Iterable<Object[]> cases() {
      return CommonRuleKeyHasherTest.uniquenessTestCases(GuavaRuleKeyHasherTest::newHasher);
    }
  }

  public static class ConsistencyTest extends CommonRuleKeyHasherTest.ConsistencyTest<HashCode> {

    @Override
    protected GuavaRuleKeyHasher newHasher() {
      return GuavaRuleKeyHasherTest.newHasher();
    }
  }

  public static GuavaRuleKeyHasher newHasher() {
    return new GuavaRuleKeyHasher(Hashing.sha1().newHasher());
  }
}
