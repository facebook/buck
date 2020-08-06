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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.rules.coercer.concat.Concatable;
import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Provides base functionality for selector list resolvers. */
public abstract class AbstractSelectorListResolver implements SelectorListResolver {

  protected static final Object NULL_VALUE = new Object();

  private final SelectableResolver selectableResolver;

  protected AbstractSelectorListResolver(SelectableResolver selectableResolver) {
    this.selectableResolver = selectableResolver;
  }

  @Nullable
  @Override
  public <T> T resolveList(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      String attributeName,
      SelectorList<T> selectorList,
      Concatable<T> concatable,
      DependencyStack dependencyStack) {
    List<T> resolvedList = new ArrayList<>();
    for (Selector<T> selector : selectorList.getSelectors()) {
      T selectorValue =
          resolveSelector(
              configurationContext, buildTarget, dependencyStack, attributeName, selector);
      if (selectorValue != null) {
        resolvedList.add(selectorValue);
      }
    }

    return resolvedList.size() == 1 ? resolvedList.get(0) : concatable.concat(resolvedList);
  }

  @Nullable
  protected abstract <T> T resolveSelector(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      String attributeName,
      Selector<T> selector);

  /**
   * Returns the value for which the current configuration matches the key inside the select
   * dictionary
   */
  protected <T> Map<Selectable, Object> findMatchingConditions(
      SelectableConfigurationContext configurationContext,
      Selector<T> selector,
      DependencyStack dependencyStack) {
    Map<Selectable, Object> matchingConditions = new LinkedHashMap<>();

    for (Map.Entry<SelectorKey, T> entry : selector.getConditions().entrySet()) {
      handleSelector(
          configurationContext,
          matchingConditions,
          entry.getKey(),
          entry.getValue(),
          dependencyStack);
    }
    for (SelectorKey selectorKey : selector.getNullConditions()) {
      handleSelector(
          configurationContext, matchingConditions, selectorKey, NULL_VALUE, dependencyStack);
    }
    return matchingConditions;
  }

  private void handleSelector(
      SelectableConfigurationContext configurationContext,
      Map<Selectable, Object> matchingConditions,
      SelectorKey selectorKey,
      Object value,
      DependencyStack dependencyStack) {
    if (selectorKey.isReserved()) {
      return;
    }

    Selectable selectable =
        selectableResolver.getSelectable(selectorKey.getBuildTarget(), dependencyStack);

    if (selectable.matches(configurationContext, dependencyStack)) {
      updateConditions(matchingConditions, selectable, value);
    }
  }

  private static void updateConditions(
      Map<Selectable, Object> matchingConditions, Selectable newCondition, Object value) {
    // Skip the new condition if some existing condition refines it
    if (matchingConditions.keySet().stream()
        .anyMatch(condition -> condition.refines(newCondition))) {
      return;
    }
    // Remove existing conditions that are refined by the new condition
    matchingConditions.keySet().removeIf(newCondition::refines);
    matchingConditions.put(newCondition, value);
  }

  /**
   * Assertion that ensures that the current configuration doesn't match multiple select dictionary
   * keys
   */
  protected static void assertNotMultipleMatches(
      Map<Selectable, ?> matchingConditions, String attributeName, BuildTarget buildTarget) {
    if (matchingConditions.size() > 1) {
      throw new HumanReadableException(
          "Multiple matches found when resolving configurable attribute \"%s\" in %s:\n%s"
              + "\nMultiple matches are not allowed unless one is unambiguously more specialized.",
          attributeName, buildTarget, Joiner.on("\n").join(matchingConditions.keySet()));
    }
  }

  /** Assertion for the selector to contain "DEFAULT" as key inside the dictionary. */
  protected static void assertSelectorHasDefault(
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      String attributeName,
      Selector<?> selector) {
    if (selector.hasDefaultCondition()) {
      return;
    }

    String noMatchMessage =
        "None of the conditions in attribute \""
            + attributeName
            + "\" of "
            + buildTarget
            + " match the configuration";
    if (selector.getNoMatchMessage().isEmpty()) {
      Iterable<?> keys =
          selector.getConditions().keySet().stream()
              .filter(key -> !key.isReserved())
              .map(SelectorKey::getBuildTarget)
              .collect(Collectors.toList());
      noMatchMessage += ".\nChecked conditions:\n " + Joiner.on("\n ").join(keys);
    } else {
      noMatchMessage += ": " + selector.getNoMatchMessage();
    }
    throw new HumanReadableException(dependencyStack, noMatchMessage);
  }
}
