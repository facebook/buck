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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;

/** Provides access to rule types and descriptions for both native and user defined rules. */
public class HybridKnownRuleTypes implements KnownRuleTypes {
  private final KnownRuleTypes nativeRuleTypes;
  private final KnownRuleTypes userDefinedRuleTypes;

  public HybridKnownRuleTypes(KnownRuleTypes nativeRuleTypes, KnownRuleTypes userDefinedRuleTypes) {
    this.nativeRuleTypes = nativeRuleTypes;
    this.userDefinedRuleTypes = userDefinedRuleTypes;
  }

  /**
   * Get a {@link RuleType} for either a native or user defined rule, depending on the identifier.
   *
   * @param name The identifier from the "buck.type" implicit attribute of a rule instance. For
   *     native rules, this will be a python identifier. For user defined rules, this may or may not
   *     be the case.
   * @return The {@link RuleType} for either a native or a user defined rule.
   */
  @Override
  public RuleType getRuleType(String name) {
    if (UserDefinedRuleNames.isUserDefinedRuleIdentifier(name)) {
      return userDefinedRuleTypes.getRuleType(name);
    } else {
      return nativeRuleTypes.getRuleType(name);
    }
  }

  /**
   * Get the Description class for a given {@link RuleType}
   *
   * @param ruleType
   * @return The {@link BaseDescription} to use for the given {@link RuleType}
   */
  @Override
  public BaseDescription<?> getDescription(RuleType ruleType) {
    if (UserDefinedRuleNames.isUserDefinedRuleIdentifier(ruleType.getName())) {
      return userDefinedRuleTypes.getDescription(ruleType);
    } else {
      return nativeRuleTypes.getDescription(ruleType);
    }
  }

  @Override
  public <T extends ConstructorArg> DataTransferObjectDescriptor<T> getConstructorArgDescriptor(
      TypeCoercerFactory typeCoercerFactory, RuleType ruleType, Class<T> dtoClass) {
    if (UserDefinedRuleNames.isUserDefinedRuleIdentifier(ruleType.getName())) {
      return userDefinedRuleTypes.getConstructorArgDescriptor(
          typeCoercerFactory, ruleType, dtoClass);
    } else {
      return nativeRuleTypes.getConstructorArgDescriptor(typeCoercerFactory, ruleType, dtoClass);
    }
  }
}
