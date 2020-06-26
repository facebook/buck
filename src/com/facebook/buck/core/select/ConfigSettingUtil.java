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
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utilities to work with {@code config_setting}. */
public class ConfigSettingUtil {

  /** Check that a set of select keys resolve unambiguously. */
  public static void checkUnambiguous(
      ImmutableList<Pair<ConfigSettingSelectable, Object>> keys, DependencyStack dependencyStack) {
    if (keys.size() <= 1) {
      return;
    }

    for (int i = 0; i != keys.size(); i++) {
      Pair<ConfigSettingSelectable, Object> a = keys.get(i);
      for (int j = i + 1; j != keys.size(); j++) {
        Pair<ConfigSettingSelectable, Object> b = keys.get(j);

        if (!haveAtLeastOneDifferentConstraint(a.getFirst(), b.getFirst())) {
          // linux-clang refines linux, these are not ambiguous
          if (a.getFirst().refines(b.getFirst()) || b.getFirst().refines(a.getFirst())) {
            continue;
          }

          // if linux-clang exists which refines both linux and clang,
          // we do not consider linux and clang unambiguous
          if (keys.stream()
              .anyMatch(
                  k -> k.getFirst().refines(a.getFirst()) && k.getFirst().refines(b.getFirst()))) {
            continue;
          }

          // Make output stable
          String keysSorted =
              Stream.of(a.getSecond(), b.getSecond())
                  .map(Object::toString)
                  .sorted()
                  .collect(Collectors.joining(" and "));
          throw new HumanReadableException(
              dependencyStack,
              "Ambiguous keys in select: %s; "
                  + "keys must have at least one different constraint or config property",
              keysSorted);
        }
      }
    }
  }

  /**
   * Check if two sets of constraint values are unambiguous (cannot match both or one is more
   * specific than another).
   */
  private static boolean haveAtLeastOneDifferentConstraint(
      ConfigSettingSelectable a, ConfigSettingSelectable b) {
    if (mapsHaveSameKeyDifferentValues(a.getValues(), b.getValues())) {
      return true;
    }
    if (mapsHaveSameKeyDifferentValues(a.getConstraintValueMap(), b.getConstraintValueMap())) {
      return true;
    }

    return false;
  }

  private static <K, V> boolean mapsHaveSameKeyDifferentValues(Map<K, V> a, Map<K, V> b) {
    Map<K, V> smaller;
    Map<K, V> larger;
    if (a.size() < b.size()) {
      smaller = a;
      larger = b;
    } else {
      smaller = b;
      larger = a;
    }

    for (Map.Entry<K, V> smallerEntry : smaller.entrySet()) {
      K key = smallerEntry.getKey();
      V smallerValue = smallerEntry.getValue();
      V largerValue = larger.get(key);
      if (largerValue != null && !smallerValue.equals(largerValue)) {
        return true;
      }
    }

    return false;
  }

  private static <K, V> boolean isSubset(Map<K, V> a, Map<K, V> b) {
    if (a.isEmpty()) {
      return true;
    }
    if (a.size() > b.size()) {
      return false;
    }
    return a.entrySet().stream().allMatch(e -> b.entrySet().contains(e));
  }

  /** Check is one configuration space is a subset of another configuration space. */
  static boolean isSubset(ConfigSettingSelectable a, ConfigSettingSelectable b) {
    return isSubset(b.getValues(), a.getValues())
        && isSubset(b.getConstraintValueMap(), a.getConstraintValueMap());
  }

  /** Check is one configuration space is a subset of another configuration space. */
  public static boolean isSubset(ConfigSettingSelectable a, AnySelectable b) {
    return b.getSelectables().stream().anyMatch(bs -> isSubset(a, bs));
  }

  /** Check is one configuration space is a subset of another configuration space. */
  public static boolean isSubset(AnySelectable a, AnySelectable b) {
    // shortcut
    if (b == AnySelectable.any()) {
      return true;
    }

    return a.getSelectables().stream().allMatch(k -> isSubset(k, b));
  }
}
