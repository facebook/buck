/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.multitenant.importer;

import com.facebook.buck.core.model.RuleType;

/**
 * {@link com.facebook.buck.core.model.AbstractRuleType.Kind} does not appear to be accessible via
 * {@link RuleType} in Kotlin as it is in Java. To that end, we create this special factory for
 * {@link RuleType} objects so that {@code IndexBuilder} can create instances of {@link RuleType}
 * based on the {@code buck.type} it deserializes from JSON.
 *
 * <p><strong>This is not designed for general use in the Buck codebase. Consider {@code
 * com.facebook.buck.core.model.impl.RuleTypeFactory} instead.</strong>
 */
public class RuleTypeFactory {

  private RuleTypeFactory() {}

  public static RuleType createBuildRule(String name) {
    return RuleType.of(name, RuleType.Kind.BUILD);
  }
}
