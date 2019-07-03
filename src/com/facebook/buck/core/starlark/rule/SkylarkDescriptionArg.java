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
package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.starlark.coercer.SkylarkDescriptionArgBuilder;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;

/**
 * Description arg for user defined rules. Instead of using reflection and immutables, this class
 * uses a backing store of attribute names -> coerced values, and makes the user's implementation
 * function available
 */
public class SkylarkDescriptionArg implements SkylarkDescriptionArgBuilder {
  private boolean attrValuesAreMutable = true;
  private final SkylarkUserDefinedRule rule;
  private final Map<String, Object> coercedAttrValues;

  /**
   * Create an instance of {@link SkylarkDescriptionArg}
   *
   * @param rule the rule that should be used to determine acceptable attributes for a target, and
   *     to provide access to the user's implementation function
   */
  public SkylarkDescriptionArg(SkylarkUserDefinedRule rule) {
    this.rule = rule;
    this.coercedAttrValues = new HashMap<>(rule.getAttrs().size());
  }

  @Override
  public void setPostCoercionValue(String attr, Object value) {
    Preconditions.checkState(
        rule.getAttrs().containsKey(attr),
        "Tried to set attribute %s, but it was not one of the attributes for %s",
        attr,
        rule.getName());
    Preconditions.checkState(
        attrValuesAreMutable,
        "Tried to set attribute %s value after building an instance of %s",
        attr,
        rule.getName());
    coercedAttrValues.put(attr, value);
  }

  @Override
  public Object getPostCoercionValue(String attr) {
    return Preconditions.checkNotNull(
        coercedAttrValues.get(attr),
        "Tried to get value of an attribute '%s' that did not have a value set yet",
        attr);
  }

  /**
   * 'Build' the {@link SkylarkDescriptionArg}. After this has been called, {@link
   * #setPostCoercionValue(String, Object)} may not be called.
   */
  public void build() {
    attrValuesAreMutable = false;
  }

  /**
   * Get the {@link SkylarkDescriptionArg} that has information about parameters and the user's
   * implementation function
   */
  public SkylarkUserDefinedRule getRule() {
    return rule;
  }

  Map<String, Object> getCoercedAttrValues() {
    return java.util.Collections.unmodifiableMap(coercedAttrValues);
  }
}
