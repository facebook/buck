/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Common interface for rule args that have a bundle name that can optionally be overridden
 * depending on the flavors a rule is built with.
 */
public interface HasBundleName {

  /** Computes the bundle name as configured, or falls back to a default name. */
  default String computeBundleName(
      ImmutableSortedSet<Flavor> flavors, Supplier<String> defaultName) {
    for (Pair<Flavor, String> nameForFlavor : getBundleNameForFlavor()) {
      if (flavors.contains(nameForFlavor.getFirst())) {
        return nameForFlavor.getSecond();
      }
    }
    return getBundleName().orElseGet(defaultName);
  }

  /** The name of the bundle. */
  Optional<String> getBundleName();

  /** A mapping from flavors to bundle names. */
  ImmutableList<Pair<Flavor, String>> getBundleNameForFlavor();
}
