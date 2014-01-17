/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public class IdentityTypeCoercer<T> extends LeafTypeCoercer<T> {
  private Class<T> type;

  IdentityTypeCoercer(Class<T> type) {
    this.type = Preconditions.checkNotNull(type);
  }

  @Override
  public Class<T> getOutputClass() {
    return type;
  }

  @Override
  public T coerce(
      BuildRuleResolver buildRuleResolver, Path pathRelativeToProjectRoot, Object object)
      throws CoerceFailedException {
    if (type.isAssignableFrom(object.getClass())) {
      return type.cast(object);
    }
    throw CoerceFailedException.simple(pathRelativeToProjectRoot, object, getOutputClass());
  }
}
