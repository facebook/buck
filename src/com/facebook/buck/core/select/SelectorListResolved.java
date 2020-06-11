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

package com.facebook.buck.core.select;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * Like {@link com.facebook.buck.core.select.SelectorList} but with all targets resolved to {@link
 * ConfigSettingSelectable}.
 */
public class SelectorListResolved<T> {
  private final ImmutableList<SelectorResolved<T>> selectors;

  public SelectorListResolved(ImmutableList<SelectorResolved<T>> selectors) {
    this.selectors = selectors;
  }

  /** Evaluate selector list to a single value (evaluate each item and concatenate). */
  public T eval(
      SelectableConfigurationContext configurationContext,
      Concatable<T> concatable,
      BuildTarget buildTarget,
      String attributeName,
      DependencyStack dependencyStack) {
    List<T> resolvedList = new ArrayList<>();
    for (SelectorResolved<T> selector : getSelectors()) {
      T selectorValue =
          selector.eval(configurationContext, buildTarget, attributeName, dependencyStack);
      if (selectorValue != null) {
        resolvedList.add(selectorValue);
      }
    }

    return resolvedList.size() == 1 ? resolvedList.get(0) : concatable.concat(resolvedList);
  }

  public ImmutableList<SelectorResolved<T>> getSelectors() {
    return selectors;
  }

  /** Unresolve. */
  public SelectorList<T> toSelectorList() {
    return new SelectorList<>(
        selectors.stream()
            .map(SelectorResolved::toSelector)
            .collect(ImmutableList.toImmutableList()));
  }
}
