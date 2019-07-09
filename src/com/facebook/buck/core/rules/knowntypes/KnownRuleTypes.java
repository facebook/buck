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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.rules.coercer.ConstructorArgBuilder;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;

/** Provides access to rule types and descriptions for both native and user defined rules. */
public interface KnownRuleTypes {

  /**
   * Get a {@link RuleType} for either a native or user defined rule, depending on the identifier.
   *
   * @param name The identifier from the "buck.type" implicit attribute of a rule instance. For
   *     native rules, this will be a python identifier. For user defined rules, this may or may not
   *     be the case.
   * @return The {@link RuleType} for either a native or a user defined rule.
   */
  RuleType getRuleType(String name);

  /**
   * Get the Description class for a given {@link RuleType}
   *
   * @param ruleType The type of the rule from {@link #getRuleType(String)}
   * @return The {@link BaseDescription} to use for the given {@link RuleType}
   */
  BaseDescription<?> getDescription(RuleType ruleType);

  /**
   * Get a builder that helps create constructor args for descriptions. This with get either a
   * reflection based builder, or a skylark builder, depending on {@code RuleType}
   */
  <T> ConstructorArgBuilder<T> getConstructorArgBuilder(
      TypeCoercerFactory typeCoercerFactory,
      RuleType ruleType,
      Class<T> dtoClass,
      BuildTarget buildTarget);
}
