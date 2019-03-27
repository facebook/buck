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

package com.facebook.buck.core.model;

/**
 * Helper that is particularly important for Kotlin code because apparently {@link
 * AbstractRuleType.Kind} is not visible to it, even if it is referenced as <code>RuleType.Kind
 * </code>, because <code>Kind</code> is declared inside a class that is package-private and Kotlin
 * visibility rules work differently.
 */
public class FakeRuleTypeForTests {

  private FakeRuleTypeForTests() {}

  public static RuleType createFakeBuildRuleType(String name) {
    return RuleType.of(name, RuleType.Kind.BUILD);
  }
}
