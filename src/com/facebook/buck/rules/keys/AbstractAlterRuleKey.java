/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

abstract class AbstractAlterRuleKey implements AlterRuleKey {
  protected final Field field;

  /**
   * @param field {@link java.lang.reflect.Field} that is assumed to be accessible.
   */
  public AbstractAlterRuleKey(Field field) {
    this.field = field;
  }

  @Nullable
  protected Object getValue(Field field, BuildRule from) {
    try {
      return field.get(from);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
