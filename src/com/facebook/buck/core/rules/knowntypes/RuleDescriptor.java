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
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import java.util.function.Function;

/**
 * Everything about Buck rule:
 *
 * <ul>
 *   <li>rule name
 *   <li>rule type
 *   <li>constructor arg type
 *   <li>constructor arg descriptor
 *   <li>rule implementation (rule description)
 * </ul>
 */
@BuckStyleValue
public abstract class RuleDescriptor<T extends ConstructorArg> {

  public abstract RuleType getRuleType();

  public abstract BaseDescription<T> getDescription();

  public abstract Function<TypeCoercerFactory, DataTransferObjectDescriptor<T>> getDtoDescriptor();

  public Class<T> getConstructorArgType() {
    return getDescription().getConstructorArgType();
  }

  public DataTransferObjectDescriptor<T> dataTransferObjectDescriptor(
      TypeCoercerFactory typeCoercerFactory) {
    return getDtoDescriptor().apply(typeCoercerFactory);
  }

  public static <T extends ConstructorArg> RuleDescriptor<T> of(
      RuleType ruleType,
      BaseDescription<T> description,
      Function<TypeCoercerFactory, DataTransferObjectDescriptor<T>> dtoDescriptor) {
    return ImmutableRuleDescriptor.of(ruleType, description, dtoDescriptor);
  }
}
