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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorKey;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.SelectorValue;
import java.nio.file.Path;
import java.util.List;

/** A factory to create {@link SelectorList} from raw (non-coerced) data. */
public class SelectorListFactory {

  private final SelectorFactory selectorFactory;

  public SelectorListFactory(SelectorFactory selectorFactory) {
    this.selectorFactory = selectorFactory;
  }

  /**
   * Create {@link SelectorList} using the given elements to create Selectors.
   *
   * @param elements a list of elements in a format produced after parsing build files (i.e.
   *     non-coerced.)
   * @param elementTypeCoercer coercer that is used to coerce values of the list
   */
  public <T> SelectorList<T> create(
      CellPathResolver cellPathResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      List<Object> elements,
      TypeCoercer<T> elementTypeCoercer)
      throws CoerceFailedException {
    assertElementTypeSupportsConcatenation(elements, elementTypeCoercer);

    ImmutableList.Builder<Selector<T>> builder = ImmutableList.builder();
    for (Object element : elements) {
      if (element instanceof SelectorValue) {
        SelectorValue selectorValue = (SelectorValue) element;
        @SuppressWarnings("unchecked")
        ImmutableMap<String, ?> rawAttributes =
            (ImmutableMap<String, ?>) selectorValue.getDictionary();
        builder.add(
            selectorFactory.createSelector(
                cellPathResolver,
                filesystem,
                pathRelativeToProjectRoot,
                rawAttributes,
                elementTypeCoercer,
                selectorValue.getNoMatchError()));
      } else {
        builder.add(
            selectorFactory.createSelector(
                cellPathResolver,
                filesystem,
                pathRelativeToProjectRoot,
                ImmutableMap.of(SelectorKey.DEFAULT_KEYWORD, element),
                elementTypeCoercer));
      }
    }

    return new SelectorList<>(elementTypeCoercer, builder.build());
  }

  private static <T> void assertElementTypeSupportsConcatenation(
      List<Object> elements, TypeCoercer<T> elementTypeCoercer) {
    if (elements.size() > 1 && elementTypeCoercer.concat(ImmutableList.of()) == null) {
      throw new HumanReadableException(
          String.format(
              "type '%s' doesn't support select concatenation",
              elementTypeCoercer.getOutputClass()));
    }
  }
}
