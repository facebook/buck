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
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Provides base functionality for selector list resolvers. */
public abstract class AbstractSelectorListResolver implements SelectorListResolver {

  protected static final Object NULL_VALUE = new Object();

  private final SelectableResolver selectableResolver;

  protected AbstractSelectorListResolver(SelectableResolver selectableResolver) {
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
  protected <T> SelectorResolved<T> resolveSelector(
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

  protected <T> SelectorListResolved<T> resolveSelectorList(
      SelectorList<T> selectorList, DependencyStack dependencyStack) {
    return selectorList.mapToResolved(s -> this.resolveSelector(s, dependencyStack));
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
    SelectorListResolved<T> selectorListResolved;
    try {
      selectorListResolved = resolveSelectorList(selectorList, dependencyStack);
    } catch (HumanReadableException e) {
      throw new HumanReadableException(
          e,
          dependencyStack,
          "When checking configurable attribute \"%s\" in %s: %s",
          attributeName,
          buildTarget.getUnflavoredBuildTarget(),
          e.getMessage());
    }

    List<T> resolvedList = new ArrayList<>();
    for (SelectorResolved<T> selector : selectorListResolved.getSelectors()) {
      T selectorValue =
          resolveSelectorValue(
              configurationContext, buildTarget, dependencyStack, attributeName, selector);
      if (selectorValue != null) {
        resolvedList.add(selectorValue);
      }
    }

    return resolvedList.size() == 1 ? resolvedList.get(0) : concatable.concat(resolvedList);
  }

  @Nullable
  protected abstract <T> T resolveSelectorValue(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      String attributeName,
      SelectorResolved<T> selector);

  /**
   * Returns the value for which the current configuration matches the key inside the select
   * dictionary
   */
  protected <T> Map<NamedSelectable, Object> findMatchingConditions(
      SelectableConfigurationContext configurationContext,
      SelectorResolved<T> selector,
      DependencyStack dependencyStack) {
    Map<NamedSelectable, Object> matchingConditions = new LinkedHashMap<>();

    for (Map.Entry<SelectorKey, SelectorResolved.Resolved<T>> entry :
        selector.getConditions().entrySet()) {
      handleSelector(
          configurationContext,
          matchingConditions,
          entry.getKey(),
          entry.getValue(),
          dependencyStack);
    }
    return matchingConditions;
  }

  private void handleSelector(
      SelectableConfigurationContext configurationContext,
      Map<NamedSelectable, Object> matchingConditions,
      SelectorKey selectorKey,
      SelectorResolved.Resolved<?> value,
      DependencyStack dependencyStack) {
    NamedSelectable selectable =
        NamedSelectable.of(selectorKey.getBuildTarget(), value.getSelectable());

    if (selectable
        .getSelectable()
        .matchesPlatform(
            configurationContext.getPlatform(),
            configurationContext.getBuckConfig(),
            dependencyStack)) {
      updateConditions(
          matchingConditions,
          selectable,
          value.getOutput().isPresent() ? value.getOutput().get() : NULL_VALUE);
    }
  }

  private static void updateConditions(
      Map<NamedSelectable, Object> matchingConditions, NamedSelectable newCondition, Object value) {
    // Skip the new condition if some existing condition refines it
    if (matchingConditions.keySet().stream()
        .anyMatch(condition -> condition.getSelectable().refines(newCondition.getSelectable()))) {
      return;
    }
    // Remove existing conditions that are refined by the new condition
    matchingConditions
        .keySet()
        .removeIf(s -> newCondition.getSelectable().refines(s.getSelectable()));
    matchingConditions.put(newCondition, value);
  }

  /**
   * Assertion that ensures that the current configuration doesn't match multiple select dictionary
   * keys
   */
  protected static void assertNotMultipleMatches(
      Map<NamedSelectable, ?> matchingConditions, String attributeName, BuildTarget buildTarget) {
    if (matchingConditions.size() > 1) {
      throw new HumanReadableException(
          "Multiple matches found when resolving configurable attribute \"%s\" in %s:\n%s"
              + "\nMultiple matches are not allowed unless one is unambiguously more specialized.",
          attributeName,
          buildTarget,
          Joiner.on("\n")
              .join(
                  matchingConditions.keySet().stream()
                      .map(
                          s ->
                              s.getBuildTarget()
                                  .map(BuildTarget::getFullyQualifiedName)
                                  .orElse("DEFAULT"))
                      .collect(ImmutableList.toImmutableList())));
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
              .sorted()
              .collect(Collectors.toList());
      noMatchMessage += ".\nChecked conditions:\n " + Joiner.on("\n ").join(keys);
    } else {
      noMatchMessage += ": " + selector.getNoMatchMessage();
    }
    throw new HumanReadableException(dependencyStack, noMatchMessage);
  }
}
