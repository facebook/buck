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

import com.facebook.buck.core.description.arg.ConstructorArg;
import com.google.common.base.Preconditions;

/** Provides access to rule types and descriptions for both native and user defined rules. */
public interface KnownRuleTypes {

  /** Get rule type, constructor arg and description object for by rule name. */
  RuleDescriptor<?> getDescriptorByName(String name);

  /** Safer version of {@link #getDescriptorByName(String)}. */
  @SuppressWarnings("unchecked")
  default <T extends ConstructorArg> RuleDescriptor<T> getDescriptorByNameChecked(
      String name, Class<T> constructorArg) {
    RuleDescriptor<?> descriptor = getDescriptorByName(name);
    Preconditions.checkState(descriptor.getDescription().getConstructorArgType() == constructorArg);
    return (RuleDescriptor<T>) descriptor;
  }
}
