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

package com.facebook.buck.features.apple.common;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Common utility methods for use in Apple Project. TODO(chatatap): There may be a better place for
 * this and it may be already exist.
 */
public class Utils {

  /**
   * Filters an iterable returning distinct values until they are changed from the previous value.
   * For example, in the list [1, 2, 3, 3, 4, 3, 4, 5] it returns [1, 2, 3, 4, 3, 4, 5].
   *
   * @param iterable The iterable to filter
   * @param <T> Any object type implementing equals.
   * @return An iterable filtered with distinct values until changed.
   */
  @VisibleForTesting
  public static <T> Iterable<T> distinctUntilChanged(Iterable<T> iterable) {
    Iterator<T> iterator = iterable.iterator();
    if (!iterator.hasNext()) {
      return new ArrayList<T>(0);
    }
    T previousItem = iterator.next();
    List<T> filteredList = new ArrayList<T>(Arrays.asList(previousItem));
    while (iterator.hasNext()) {
      T item = iterator.next();
      if (!item.equals(previousItem)) {
        filteredList.add(item);
      }
      previousItem = item;
    }
    return filteredList;
  }
}
