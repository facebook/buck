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
import com.facebook.buck.core.select.AbstractSelectorListResolver;
import com.facebook.buck.core.select.Selectable;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorListResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A {@link SelectorListResolver} which handles selectors differently by concatenating all list
 * values of the dictionary and maintains default behavior for values of different types.
 */
public class UnconfiguredSelectorListResolver extends AbstractSelectorListResolver {

  public UnconfiguredSelectorListResolver(SelectableResolver selectableResolver) {
    super(selectableResolver);
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T resolveSelector(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      String attributeName,
      Selector<T> selector) {
    Map<Selectable, Object> matchingConditions =
        findMatchingConditions(configurationContext, selector, dependencyStack);

    Object matchingResult = null;
    assertNotMultipleMatches(matchingConditions, attributeName, buildTarget);
    if (matchingConditions.size() == 1) {
      matchingResult = Iterables.getOnlyElement(matchingConditions.values());
    }

    if (matchingResult == null) {
      assertSelectorHasDefault(buildTarget, dependencyStack, attributeName, selector);
      matchingResult = selector.getDefaultConditionValue();
    }

    if (matchingResult == null) {
      return null;
    }

    ImmutableList.Builder<String> builder = new ImmutableList.Builder<String>();
    if (selector.getDefaultConditionValue() instanceof ImmutableList) {
      ImmutableMap<SelectorKey, ImmutableList<String>> conditions =
          (ImmutableMap<SelectorKey, ImmutableList<String>>) selector.getConditions();

      for (ImmutableList<String> list : conditions.values()) {
        builder.addAll(list);
      }

      return (T) builder.build();
    }

    return (T) matchingResult;
  }
}
