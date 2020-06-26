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

import com.facebook.buck.core.exceptions.HumanReadableException;
import java.util.Map;

/** Utilities to work with {@code compatible_with} target attribute. */
public class CompatibleWithUtil {
  private static void checkCompatibleWithIsSubsetOfSelectKeys(
      LabelledAnySelectable compatibleWith, SelectorResolved<?> selector) {
    AnySelectable selectKeys = selector.keys();

    // shortcut
    if (selectKeys == AnySelectable.any()) {
      return;
    }

    for (Map.Entry<Object, ConfigSettingSelectable> compatibleWithItem :
        compatibleWith.getSelectables().entrySet()) {
      if (!ConfigSettingUtil.isSubset(compatibleWithItem.getValue(), selectKeys)) {
        throw new HumanReadableException(
            "select keys space must be a superset of compatible_with space;"
                + " uncovered compatible_with entry: %s",
            compatibleWithItem.getKey());
      }
    }
  }

  /** Check select resolution will not fail if a target is {@code compatible_with}. */
  public static void checkCompatibleWithIsSubsetOfSelectKeys(
      LabelledAnySelectable compatibleWith, SelectorListResolved<?> selectorList) {
    for (SelectorResolved<?> selector : selectorList.getSelectors()) {
      checkCompatibleWithIsSubsetOfSelectKeys(compatibleWith, selector);
    }
  }
}
