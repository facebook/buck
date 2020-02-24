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

import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.collect.ImmutableList;
import java.util.Objects;

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

  private final ImmutableList<Selector<T>> selectors;

  public SelectorList(ImmutableList<Selector<T>> selectors) {
    this.selectors = selectors;
  }

  /**
   * @return a syntactically order-preserved list of all values and selectors for this attribute.
   */
  public ImmutableList<Selector<T>> getSelectors() {
    return selectors;
  }

  /** Transform all items with given function. */
  public <U, E extends Exception> SelectorList<U> mapValuesThrowing(
      ThrowingFunction<T, U, E> function) throws E {
    ImmutableList.Builder<Selector<U>> selectors =
        ImmutableList.builderWithExpectedSize(this.selectors.size());
    for (Selector<T> selector : this.selectors) {
      selectors.add(selector.mapValuesThrowing(function));
    }

    return new SelectorList<>(selectors.build());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SelectorList<?> that = (SelectorList<?>) o;
    return selectors.equals(that.selectors);
  }

  @Override
  public int hashCode() {
    return Objects.hash(selectors);
  }

  @Override
  public String toString() {
    return "SelectorList{" + ", selectors=" + selectors + '}';
  }
}
