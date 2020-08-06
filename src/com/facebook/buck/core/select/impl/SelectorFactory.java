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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** Factory to create {@link Selector} using raw (non-coerced) data. */
public class SelectorFactory {

  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory;

  public SelectorFactory(UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetViewFactory) {
    this.unconfiguredBuildTargetViewFactory = unconfiguredBuildTargetViewFactory;
  }

  /** Creates a new Selector using the default error message when no conditions match. */
  public Selector<Object> createSelector(
      CellNameResolver cellNameResolver,
      ForwardRelativePath pathRelativeToProjectRoot,
      Map<String, ?> rawAttributes) {
    return createSelector(cellNameResolver, pathRelativeToProjectRoot, rawAttributes, "");
  }

  /**
   * Creates a {@link Selector} by converting a given map.
   *
   * @param rawAttributes a map with attributes represented in a format produced by build file
   *     parsers (i.e. non-coerced.)
   */
  public Selector<Object> createSelector(
      CellNameResolver cellNameResolver,
      ForwardRelativePath pathRelativeToProjectRoot,
      Map<String, ?> rawAttributes,
      String noMatchMessage) {
    LinkedHashMap<SelectorKey, Object> result =
        Maps.newLinkedHashMapWithExpectedSize(rawAttributes.size());
    Set<SelectorKey> nullConditions = new HashSet<>();
    for (Entry<String, ?> entry : rawAttributes.entrySet()) {
      String key = entry.getKey();
      SelectorKey selectorKey;
      if (key.equals(SelectorKey.DEFAULT_KEYWORD)) {
        selectorKey = SelectorKey.DEFAULT;
      } else {
        selectorKey =
            new SelectorKey(
                ConfigurationBuildTargets.convert(
                    unconfiguredBuildTargetViewFactory.createForPathRelativeToProjectRoot(
                        pathRelativeToProjectRoot, key, cellNameResolver)));
      }
      if (entry.getValue() == Runtime.NONE) {
        result.remove(selectorKey);
        nullConditions.add(selectorKey);
      } else {
        result.put(selectorKey, entry.getValue());
        nullConditions.remove(selectorKey);
      }
    }

    return new Selector<>(
        ImmutableMap.copyOf(result), ImmutableSet.copyOf(nullConditions), noMatchMessage);
  }
}
