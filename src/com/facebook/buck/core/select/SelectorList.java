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

package com.facebook.buck.core.select;

import com.facebook.buck.rules.coercer.concat.Concatable;
import java.util.List;

/**
 * Represents a list of {@link Selector} objects
 *
 * <p>This is used to represent an expression that can contain a mix of primitive values and
 * selectable expressions:
 *
 * <pre>
 *   deps = [
 *     ":dep1",
 *   ] + select(
 *     "//condition1": ["dep2", "dep3],
 *     ...
 *   ) + select(
 *     "//condition2": ["dep4", "dep5],
 *     ...
 *   )
 * </pre>
 *
 * @param <T> the type of objects the underlying selectors provide after resolution
 */
public final class SelectorList<T> {
  private final Concatable<T> elementTypeConcatable;
  private final List<Selector<T>> selectors;

  public SelectorList(Concatable<T> elementTypeConcatable, List<Selector<T>> selectors) {
    this.elementTypeConcatable = elementTypeConcatable;
    this.selectors = selectors;
  }

  /**
   * @return a syntactically order-preserved list of all values and selectors for this attribute.
   */
  public List<Selector<T>> getSelectors() {
    return selectors;
  }

  /**
   * @return {@link Concatable} that should be used to produce a final result by concatenating the
   *     results of individual selectors in this list.
   */
  public Concatable<T> getConcatable() {
    return elementTypeConcatable;
  }
}
