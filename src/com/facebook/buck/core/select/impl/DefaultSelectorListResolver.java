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

package com.facebook.buck.core.select.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.select.Selectable;
import com.facebook.buck.core.select.SelectableResolver;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A {@link SelectorListResolver} that finds the most specialized condition in the given list and
 * concatenates the results.
 */
public class DefaultSelectorListResolver implements SelectorListResolver {

  private final SelectableResolver selectableResolver;

  public DefaultSelectorListResolver(SelectableResolver selectableResolver) {
    this.selectableResolver = selectableResolver;
  }

  @Override
  public <T> T resolveList(
      BuildTarget buildTarget, String attributeName, SelectorList<T> selectorList) {
    List<T> resolvedList = new ArrayList<>();
    for (Selector<T> selector : selectorList.getSelectors()) {
      T selectorValue = resolveSelector(buildTarget, attributeName, selector);
      if (selectorValue != null) {
        resolvedList.add(selectorValue);
      }
    }
    return resolvedList.size() == 1
        ? resolvedList.get(0)
        : selectorList.getConcatable().concat(resolvedList);
  }

  @Nullable
  private <T> T resolveSelector(
      BuildTarget buildTarget, String attributeName, Selector<T> selector) {
    Map<Selectable, T> matchingConditions = findMatchingConditions(selector);

    T matchingResult = null;
    assertNotMultipleMatches(matchingConditions, attributeName, buildTarget);
    if (matchingConditions.size() == 1) {
      matchingResult = Iterables.getOnlyElement(matchingConditions.values());
    }

    if (matchingResult == null) {
      assertSelectorHasDefault(attributeName, selector);
      matchingResult = selector.hasDefaultCondition() ? selector.getDefaultConditionValue() : null;
    }

    return matchingResult;
  }

  private <T> Map<Selectable, T> findMatchingConditions(Selector<T> selector) {
    Map<Selectable, T> matchingConditions = new LinkedHashMap<>();

    for (Map.Entry<SelectorKey, T> entry : selector.getConditions().entrySet()) {
      SelectorKey selectorKey = entry.getKey();
      if (selectorKey.isReserved()) {
        continue;
      }

      Selectable selectable = selectableResolver.getSelectable(selectorKey.getBuildTarget());

      if (selectable.matches()) {
        updateConditions(matchingConditions, selectable, entry.getValue());
      }
    }
    return matchingConditions;
  }

  private static <T> void updateConditions(
      Map<Selectable, T> matchingConditions, Selectable newCondition, T value) {
    // Skip the new condition if some existing condition refines it
    if (matchingConditions
        .keySet()
        .stream()
        .anyMatch(condition -> condition.refines(newCondition))) {
      return;
    }
    // Remove existing conditions that are refined by the new condition
    matchingConditions.keySet().removeIf(newCondition::refines);
    matchingConditions.put(newCondition, value);
  }

  private static void assertNotMultipleMatches(
      Map<Selectable, ?> matchingConditions, String attributeName, BuildTarget buildTarget) {
    if (matchingConditions.size() > 1) {
      throw new HumanReadableException(
          "Multiple matches found when resolving configurable attribute \"%s\" in %s:\n%s"
              + "\nMultiple matches are not allowed unless one is unambiguously more specialized.",
          attributeName, buildTarget, Joiner.on("\n").join(matchingConditions.keySet()));
    }
  }

  private static void assertSelectorHasDefault(String attributeName, Selector<?> selector) {
    if (selector.hasDefaultCondition()) {
      return;
    }

    String noMatchMessage =
        "None of the conditions in attribute \"" + attributeName + "\" match the configuration";
    if (selector.getNoMatchMessage().isEmpty()) {
      Iterable<?> keys =
          selector
              .getConditions()
              .keySet()
              .stream()
              .filter(key -> !key.isReserved())
              .map(SelectorKey::getBuildTarget)
              .collect(Collectors.toList());
      noMatchMessage += ". Checked conditions:\n " + Joiner.on("\n ").join(keys);
    } else {
      noMatchMessage += ": " + selector.getNoMatchMessage();
    }
    throw new HumanReadableException(noMatchMessage);
  }
}
