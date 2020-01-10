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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.select.Selector;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.JsonTypeConcatenatingCoercer;
import com.facebook.buck.rules.coercer.JsonTypeConcatenatingCoercerFactory;
import com.facebook.buck.rules.coercer.SingleElementJsonTypeConcatenatingCoercer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** A factory to create {@link SelectorList} from raw (non-coerced) data. */
public class SelectorListFactory {

  private final SelectorFactory selectorFactory;

  public SelectorListFactory(SelectorFactory selectorFactory) {
    this.selectorFactory = selectorFactory;
  }

  /**
   * Create {@link SelectorList} using the given elements to create Selectors.
   *
   * @param listWithSelects a list of elements in a format produced after parsing build files (i.e.
   *     non-coerced.)
   */
  public SelectorList<Object> create(
      CellPathResolver cellPathResolver,
      ForwardRelativePath pathRelativeToProjectRoot,
      ListWithSelects listWithSelects)
      throws CoerceFailedException {
    ImmutableList.Builder<Selector<Object>> builder =
        ImmutableList.builderWithExpectedSize(listWithSelects.getElements().size());
    for (Object element : listWithSelects.getElements()) {
      if (element instanceof SelectorValue) {
        SelectorValue selectorValue = (SelectorValue) element;
        ImmutableMap<String, Object> rawAttributes = selectorValue.getDictionary();
        builder.add(
            selectorFactory.createSelector(
                cellPathResolver,
                pathRelativeToProjectRoot,
                rawAttributes,
                selectorValue.getNoMatchError()));
      } else {
        builder.add(Selector.onlyDefault(element));
      }
    }

    JsonTypeConcatenatingCoercer coercer =
        JsonTypeConcatenatingCoercerFactory.createForType(listWithSelects.getType());

    if (listWithSelects.getElements().size() != 1) {
      if (coercer instanceof SingleElementJsonTypeConcatenatingCoercer) {
        throw new HumanReadableException(
            "type '%s' doesn't support select concatenation", listWithSelects.getType().getName());
      }
    }

    return new SelectorList<>(coercer, builder.build());
  }
}
