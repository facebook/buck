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
import com.facebook.buck.core.select.ConfigSettingSelectable;
import com.facebook.buck.core.select.ConfigSettingUtil;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolved;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.SelectorResolved;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link SelectorListResolver} that finds the most specialized condition in the given list and
 * concatenates the results.
 */
public class DefaultSelectorListResolver implements SelectorListResolver {

  private final SelectableResolver selectableResolver;

  public DefaultSelectorListResolver(SelectableResolver selectableResolver) {
    this.selectableResolver = selectableResolver;
  }

  private ConfigSettingSelectable resolveSelectorKey(
      SelectorKey selectorKey, DependencyStack dependencyStack) {
    return (ConfigSettingSelectable)
        selectorKey
            .getBuildTarget()
            .map(t -> selectableResolver.getSelectable(t, dependencyStack))
            .orElse(ConfigSettingSelectable.any());
  }

  /** Resolve selector keys into {@link ConfigSettingSelectable}. */
  private <T> SelectorResolved<T> resolveSelector(
      Selector<T> selector, DependencyStack dependencyStack) {
    ImmutableMap.Builder<SelectorKey, SelectorResolved.Resolved<T>> conditions =
        ImmutableMap.builder();
    for (Map.Entry<SelectorKey, T> e : selector.getConditions().entrySet()) {
      conditions.put(
          e.getKey(),
          new SelectorResolved.Resolved<T>(
              resolveSelectorKey(e.getKey(), dependencyStack), Optional.of(e.getValue())));
    }
    for (SelectorKey nullCondition : selector.getNullConditions()) {
      conditions.put(
          nullCondition,
          new SelectorResolved.Resolved<>(
              resolveSelectorKey(nullCondition, dependencyStack), Optional.empty()));
    }
    ImmutableMap<SelectorKey, SelectorResolved.Resolved<T>> conditionsMap = conditions.build();

    ConfigSettingUtil.checkUnambiguous(
        conditionsMap.entrySet().stream()
            .map(
                e ->
                    new Pair<ConfigSettingSelectable, Object>(
                        e.getValue().getSelectable(), e.getKey()))
            .collect(ImmutableList.toImmutableList()),
        dependencyStack);

    return new SelectorResolved<>(conditionsMap, selector.getNoMatchMessage());
  }

  @Override
  public <T> SelectorListResolved<T> resolveSelectorList(
      SelectorList<T> selectorList, DependencyStack dependencyStack) {
    return selectorList.mapToResolved(s -> this.resolveSelector(s, dependencyStack));
  }
}
