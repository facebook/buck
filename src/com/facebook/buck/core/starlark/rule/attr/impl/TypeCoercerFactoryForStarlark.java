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

package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.reflect.TypeToken;

/** Type coercer factory used to construct type coercer for starlark attrs. */
class TypeCoercerFactoryForStarlark {
  /** The factory. */
  private static final TypeCoercerFactory factory = new DefaultTypeCoercerFactory();

  public static <T> TypeCoercer<?, T> typeCoercerForType(TypeToken<T> type) {
    return factory.typeCoercerForType(type);
  }
}
