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
package com.facebook.buck.rules.coercer;

import javax.annotation.Nullable;

/**
 * Coercer to be used when concatenating lists of elements that don't support concatenation.
 *
 * <p>Note that this coercer can be used to produce a result from a list that contains a single
 * element.
 */
public class SingleElementJsonTypeConcatenatingCoercer extends JsonTypeConcatenatingCoercer {

  SingleElementJsonTypeConcatenatingCoercer(Class<?> elementClass) {
    super(elementClass);
  }

  @Nullable
  @Override
  public Object concat(Iterable<Object> elements) {
    return null;
  }
}
