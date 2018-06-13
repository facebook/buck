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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.syntax.Runtime;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/** Factory to create {@link Selector} using raw (non-coerced) data. */
public class SelectorFactory {

  /** Creates a new Selector using the default error message when no conditions match. */
  public <T> Selector<T> createSelector(
      CellPathResolver cellPathResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Map<String, ?> rawAttributes,
      TypeCoercer<T> elementTypeCoercer)
      throws CoerceFailedException {
    return createSelector(
        cellPathResolver,
        filesystem,
        pathRelativeToProjectRoot,
        rawAttributes,
        elementTypeCoercer,
        "");
  }

  /**
   * Creates a {@link Selector} by converting a given map.
   *
   * @param rawAttributes a map with attributes represented in a format produced by build file
   *     parsers (i.e. non-coerced.)
   * @param elementTypeCoercer coercer that is used to coerce values of the given map
   */
  public <T> Selector<T> createSelector(
      CellPathResolver cellPathResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Map<String, ?> rawAttributes,
      TypeCoercer<T> elementTypeCoercer,
      String noMatchMessage)
      throws CoerceFailedException {
    LinkedHashMap<SelectorKey, T> result =
        Maps.newLinkedHashMapWithExpectedSize(rawAttributes.size());
    boolean foundDefaultCondition = false;
    for (Entry<String, ?> entry : rawAttributes.entrySet()) {
      String key = entry.getKey();
      SelectorKey selectorKey;
      if (key.equals(SelectorKey.DEFAULT_KEYWORD)) {
        foundDefaultCondition = true;
        selectorKey = SelectorKey.DEFAULT;
      } else {
        selectorKey =
            new SelectorKey(
                BuildTargetParser.fullyQualifiedNameToBuildTarget(cellPathResolver, key));
      }
      if (entry.getValue() == Runtime.NONE) {
        result.remove(selectorKey);
      } else {
        result.put(
            selectorKey,
            elementTypeCoercer.coerce(
                cellPathResolver, filesystem, pathRelativeToProjectRoot, entry.getValue()));
      }
    }

    return new Selector<>(ImmutableMap.copyOf(result), noMatchMessage, foundDefaultCondition);
  }
}
