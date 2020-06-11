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
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * {@link com.facebook.buck.core.select.Selector} but with labels resolved to {@link
 * ConfigSettingSelectable}.
 */
public class SelectorResolved<T> {

  private static final Object NULL_VALUE = new Object();
  private final ImmutableMap<SelectorKey, Resolved<T>> conditions;
  private final String noMatchMessage;

  public SelectorResolved(
      ImmutableMap<SelectorKey, Resolved<T>> conditions, String noMatchMessage) {
    this.conditions = conditions;
    this.noMatchMessage = noMatchMessage;
  }

  /**
   * Returns the value for which the current configuration matches the key inside the select
   * dictionary
   */
  private static <T> Map<NamedSelectable, Object> findMatchingConditions(
      SelectableConfigurationContext configurationContext,
      SelectorResolved<T> selector,
      DependencyStack dependencyStack) {
    Map<NamedSelectable, Object> matchingConditions = new LinkedHashMap<>();

    for (Map.Entry<SelectorKey, Resolved<T>> entry : selector.getConditions().entrySet()) {
      handleSelector(
          configurationContext,
          matchingConditions,
          entry.getKey(),
          entry.getValue(),
          dependencyStack);
    }
    return matchingConditions;
  }

  private static void handleSelector(
      SelectableConfigurationContext configurationContext,
      Map<NamedSelectable, Object> matchingConditions,
      SelectorKey selectorKey,
      Resolved<?> value,
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
  private static void assertNotMultipleMatches(
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
  private static void assertSelectorHasDefault(
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

  /** Evaluate this selector to a single value; throw if no matches or more than one match. */
  @Nullable
  @SuppressWarnings("unchecked")
  public T eval(
      SelectableConfigurationContext configurationContext,
      BuildTarget buildTarget,
      String attributeName,
      DependencyStack dependencyStack) {

    Map<NamedSelectable, Object> matchingConditions =
        findMatchingConditions(configurationContext, this, dependencyStack);

    Object matchingResult = null;
    assertNotMultipleMatches(matchingConditions, attributeName, buildTarget);
    if (matchingConditions.size() == 1) {
      matchingResult = Iterables.getOnlyElement(matchingConditions.values());
    }

    if (matchingResult == null) {
      assertSelectorHasDefault(buildTarget, dependencyStack, attributeName, toSelector());
      matchingResult = getDefaultConditionValue();
    }

    return matchingResult == NULL_VALUE ? null : (T) matchingResult;
  }

  /** A pair of resolve selector key and selector entry output. */
  public static class Resolved<T> {
    private final ConfigSettingSelectable selectable;
    /** Empty encodes null. */
    private final Optional<T> output;

    public Resolved(ConfigSettingSelectable selectable, Optional<T> output) {
      this.selectable = selectable;
      this.output = output;
    }

    public ConfigSettingSelectable getSelectable() {
      return selectable;
    }

    public Optional<T> getOutput() {
      return output;
    }
  }

  public ImmutableMap<SelectorKey, Resolved<T>> getConditions() {
    return conditions;
  }

  public String getNoMatchMessage() {
    return noMatchMessage;
  }

  @Nullable
  public T getDefaultConditionValue() {
    Resolved<T> resolved = conditions.get(SelectorKey.DEFAULT);
    return resolved != null ? resolved.output.orElse(null) : null;
  }

  /** Unresolve. */
  public Selector<T> toSelector() {
    ImmutableMap.Builder<SelectorKey, T> conditions = ImmutableMap.builder();
    ImmutableSet.Builder<SelectorKey> nullConditions = ImmutableSet.builder();

    for (Map.Entry<SelectorKey, Resolved<T>> entry : this.conditions.entrySet()) {
      Optional<T> output = entry.getValue().output;
      if (output.isPresent()) {
        conditions.put(entry.getKey(), output.get());
      } else {
        nullConditions.add(entry.getKey());
      }
    }

    return new Selector<>(conditions.build(), nullConditions.build(), noMatchMessage);
  }
}
