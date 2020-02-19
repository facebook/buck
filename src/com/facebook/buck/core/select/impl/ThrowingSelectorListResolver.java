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

package com.facebook.buck.core.select.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.rules.coercer.concat.Concatable;
import javax.annotation.Nullable;

/** Selector list resolver which throws unconditionally */
public class ThrowingSelectorListResolver implements SelectorListResolver {

  /** Throw unconditionally */
  @Nullable
  @Override
  public <T> T resolveList(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      String attributeName,
      SelectorList<T> selectorList,
      Concatable<T> concatable,
      DependencyStack dependencyStack) {
    throw new IllegalStateException();
  }
}
