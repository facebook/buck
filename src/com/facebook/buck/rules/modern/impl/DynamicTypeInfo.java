/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.AddsToRuleKey;

/**
 * This type info delegates to a ClassInfo looked up at runtime. It is used for fields of (static)
 * type that implement AddsToRuleKey.
 */
public class DynamicTypeInfo implements ValueTypeInfo<AddsToRuleKey> {
  public static final DynamicTypeInfo INSTANCE = new DynamicTypeInfo();

  @Override
  public <E extends Exception> void visit(AddsToRuleKey value, ValueVisitor<E> visitor) throws E {
    visitor.visitDynamic(value, DefaultClassInfoFactory.forInstance(value));
  }

  @Override
  public <E extends Exception> AddsToRuleKey create(ValueCreator<E> creator) throws E {
    return creator.createDynamic();
  }
}
